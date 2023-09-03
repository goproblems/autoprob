package autoprob.go;


import java.awt.Color;
import java.awt.Point;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.Vector;

import autoprob.go.action.Action;
import autoprob.go.action.CommentAction;
import autoprob.go.action.LabelAction;
import autoprob.go.action.MoveAction;
import autoprob.go.action.SetupAction;
import autoprob.go.action.SizeAction;
import autoprob.go.action.SquareAction;
import autoprob.go.action.TriangleAction;
import autoprob.go.parse.ChoiceRecurser;
import autoprob.go.parse.FindSpecificNodeRecurser;
import autoprob.go.parse.NodeRecurser;
import autoprob.katastruct.KataAnalysisResult;

/**
 * a Node is one element in the SGF tree
 * contained within the node are subnodes (babies) and actions (acts)
 */
public class Node {
    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_PATH = 1;
    public static final int TYPE_COMMENT = 2;

    public Node mom;
    /** Vector< Node >: progeny */
    public Vector babies = new Vector();
    /** Vector< Action > : the actions this node contains, like moves or addstones */
    public Vector acts = new Vector();

    public KataAnalysisResult kres;

    /** for saving extra read-in info from the SGF */
    Vector<String> xtraTags = new Vector<String>();
    /** for saving extra read-in info from the SGF */
    Vector<String> xtraTagVals = new Vector<String>();
    String src; // how this arrived in SGF
    public boolean hasMove = false; // contains a move action
    public boolean isChoice = false; // specified as a good choice for computer response
    public boolean forceMove = false; // the user can only choose from specified paths
    public boolean notThis = false; // this node cannot be reached through normal play
    public String comment;
    public int result = 0;
    public int atlasY = -1; // position on the atlas -- x-axis is just depth
    public int depth = 0; // just how far have we descended in this infernal dungeon?
    private static Random random = new Random();
    /** was this node in the original SGF? */
    public boolean originalPath = false;
    boolean chosenPath = false;
    public int defaultToMoveColor = Intersection.BLACK;
    public Board board;
    private BoardDelta delta;
    private int pathCount;
    public int nodeType = TYPE_NORMAL;

    // basic constructor
    public Node(Node mommy) {
        learnFromMom(mommy);
    }

    private void learnFromMom(Node mommy) {
        mom = mommy;
        if (mom != null) {
            depth = mom.depth + 1; // simplicity itself
            defaultToMoveColor = (mom.getToMove() % 2) + 1;
            try {
                board = (Board) mom.board.clone();
                board.removeMarkup();
            }
            catch (CloneNotSupportedException e) {
            }
        }
        else {
            board = new Board();
        }
    }

    // construct from some SGF params
    public Node(String insrc, Node mommy) {
        learnFromMom(mommy);
        originalPath = true;
        src = insrc.trim();
        // parse it
        int i = 0;
        String lasttag = "bar";
        loop: while (i < src.length()) {
            // first get the tag
            // uppercase letters only
            String tag = "";
            try {
                while (src.charAt(i) != '[') {
                    char c = src.charAt(i);
                    if (c >= 'A' && c <= 'Z')
                        tag += c;
                    i++;
                    // might have hit end prematurely
                    if (i >= src.length()) {
                        if (tag.length() > 0)
                            System.out.println("tag never had value: " + tag + " in node: " + src);
                        break loop;
                    }
                }
                // now get value
                // stuff between []'s
                i++;
                String val = "";
                while (src.charAt(i) != ']') {
                    char c = src.charAt(i);
                    val += c;
                    i++;
                    /*			 // sometimes we have two tags stuck together -- bitch!
                     if (src.charAt(i) == ']')
                     if (i+1 < src.length())
                     if (src.charAt(i+1) == '[')
                     // compact the fucker
                      i += 2;
                      */
                }
                i++;
                
                // now process it
                if (tag.length() == 0) {
                    tag = ""+lasttag;
                    //System.out.println("empty");
                }
                //		  System.out.println("tag: " + tag + " val: " + val + " last: " + lasttag);
                
                // switch: what type of SGF tag do we hav?
                if (tag.equals("W")) {
                    addAct(new MoveAction(val, Intersection.WHITE, this));
                    defaultToMoveColor = Intersection.BLACK;
                    if (mom != null)
                    	mom.defaultToMoveColor = Intersection.WHITE;
                }
                else if (tag.equals("B")) {
                    addAct(new MoveAction(val, Intersection.BLACK, this));
                    defaultToMoveColor = Intersection.WHITE;
                    if (mom != null)
                    	mom.defaultToMoveColor = Intersection.BLACK;
                }
                else if (tag.equals("AW"))
                    addAct(new SetupAction(val, Intersection.WHITE, this));
                else if (tag.equals("AB"))
                    addAct(new SetupAction(val, Intersection.BLACK, this));
                else if (tag.equals("C"))
                    addAct(new CommentAction(val, this));
                else if (tag.equals("LB"))
                    addAct(new LabelAction(val, this));
                else if (tag.equals("TR"))
                    addAct(new TriangleAction(val, this));
                else if (tag.equals("MA") || tag.equals("SQ"))
                    addAct(new SquareAction(val, this));
                else if (tag.equals("SZ"))
                    addAct(new SizeAction(val, this));
                else {
                    if (false)
                        System.out.println("UNKNOWN tag: " + tag);
                    else {
                        if (tag.equals("CR") == false) { // CGoban2 crap
                            // save xtra tag
                            xtraTags.addElement(tag);
                            xtraTagVals.addElement(val);
                        }
                    }
                }
                
                lasttag = ""+tag; // elegant java strings
            }
            catch (Exception e) {
                System.out.println("parse error on node: " + src + " : " + e + "(tag): " + tag);
                e.printStackTrace();
                return;
            }
        }
//        System.out.println("tom: " + defaultToMoveColor + " " + getToMove() + " " + this);
    }
    
    public void addXtraTag(String tag, String val) {
        xtraTags.addElement(tag);
        xtraTagVals.addElement(val);
    }
    
    public String getXtra(String tag) {
        for (int i = 0; i < xtraTags.size(); i++) {
            if (xtraTags.elementAt(i).equals(tag))
            	return xtraTagVals.elementAt(i);
        }
    	return null;
    }

    public void addChild(Node son) {
        babies.addElement(son);
    }
    
    public void addAct(Action act) {
        acts.addElement(act);
        act.execute(board);
    }
    
    // calculate our delta from root
    public void calcDelta() {
        Node n = this;
        while (n.mom != null) n = n.mom;
        calcDelta(n.board);
    }
    
    // get root node of tree
    public Node getRoot() {
    	Node n = this;
    	while (n.mom != null) n = n.mom;
    	return n;
    }
    
    /**
     * calculate the delta for our position from given board (root)
     * @param b0 the board we compare against
     */
    private void calcDelta(Board b0) {
        int[] ar = new int[19 * 19];
        int cnt = 0;
        // examine different stones on board
        // we compress different stones into 32-bit ints, with the position and the delta
        for (int i = 0; i < 19; i++)
            for (int j = 0; j < 19; j++) {
                int d = b0.board[i][j].stone - board.board[i][j].stone;
                if (d == 0) continue;
                d += 2; // make sure not neg
                int x = j * 19 + i;
                x += d << 16;
                ar[cnt++] = x;
            }
        int[] stones = new int[cnt];
        System.arraycopy(ar, 0, stones, 0, cnt);
        // look for a ko taking move -- must be one stone removed
        boolean isKo = false;
        if (board.deathList.size() == 1) {
//            Point p = (Point) board.deathList.elementAt(0);
            Point p = findMove();
            if (p != null) {
                isKo = board.isKo(p);
            }
        }
        delta = new BoardDelta(stones, getToMove(), isKo ? findMove() : null);
//        System.out.println("deltahash: " + delta.hashCode());
    }
    
    /**
     * look through a node's actions for any move
     */
    public Point findMove() {
        for (Enumeration e = acts.elements(); e.hasMoreElements();) {
            Action a = (Action) e.nextElement();
            if (a.getMove() != null)
                return a.getMove();
        }
        return null;
    }

    /** recursively check if any branch contains RIGHT */
    public boolean searchForTheTruth() {
        Set s = new HashSet();
        return searchForTheTruth(s);
    }
    
    // recursively check if any branch contains RIGHT
    private boolean searchForTheTruth(Set s) {
        s.add(this);
        // see if we have our own truth
        if (result > 0) {
            return result == Intersection.RIGHT;
        }
        boolean res = false;
        for (Enumeration e = babies.elements(); e.hasMoreElements();) {
            Node n = (Node) e.nextElement();
            if (s.contains(n)) continue;
            res |= n.searchForTheTruth(s);
        }
        return res;
    }
    
    // recursively apply function, do action before
    public void generalRecurse(NodeRecurser r) {
        r.action(this);
        for (Enumeration e = babies.elements(); e.hasMoreElements();) {
            Node n = (Node) e.nextElement();
            n.generalRecurse(r);
        }
    }
    
    // recursively apply function, do action after
    public void generalPostRecurse(NodeRecurser r) {
        for (Enumeration e = babies.elements(); e.hasMoreElements();) {
            Node n = (Node) e.nextElement();
            n.generalRecurse(r);
        }
        r.action(this);
    }
    
    // search through actions for a MoveAction
    public MoveAction getMoveAction() {
        for (Enumeration ea = acts.elements(); ea.hasMoreElements();) {
            Action a = (Action) ea.nextElement();
            if (a instanceof MoveAction)
                return (MoveAction) a;
        }
        return null;
    }
    
    // how should the SGF respond to a user's move?
    protected Node chooseResponse() throws Exception {
        if (babies.size() == 0)
            throw new Exception("no moves!");
        
        Vector choices = new Vector();
        for (Enumeration e = babies.elements(); e.hasMoreElements();) {
            Node n = (Node) e.nextElement();
            ChoiceRecurser choiceRec = new ChoiceRecurser();
            n.generalRecurse(choiceRec);
            if (choiceRec.choice)
                choices.addElement(n);
        }
        if (choices.size() == 0) {
            Node firstEl = (Node) babies.firstElement();
            if (!firstEl.originalPath)
                return null;
            return firstEl;
        }
        
        // choose randomly from choices
        int sel = random.nextInt(choices.size());
        return (Node) choices.elementAt(sel);
    }
    
    // simple adding a move to the tree
    public Node addBasicMove(int x, int y) throws Exception {
        Node tike = new Node(this);
        // put a moveaction in tike
        tike.addAct(new MoveAction(getToMove(), tike, x, y));

        // add a baby for current node
        addChild(tike);
        
        return tike;
    }
    
    /**
     *  they clicked a square and made a valid move -- the curnode is called
     *  to see how to handle it
     */
    public void handleMove(int x, int y) throws Exception {
        // examine children, see if one reflects the move we just made
        for (Enumeration e = babies.elements(); e.hasMoreElements();) {
            Node n = (Node) e.nextElement();
            Point p = n.findMove();
            if (p != null && p.x == x && p.y == y) {
                // that's us!
                n.setResultFromMovingHere();
//                ga.globals.setCurNode(n);  // advance us to here
                return;
            }
        }

//        // didn't find the move we're looking for
//        // add this to the tree
//        Node tike = new Node(this);
//        if (ga.globals.isFreshTree()) tike.originalPath = true;
//        // put a moveaction in tike
//        tike.addAct(new MoveAction(getToMove(), tike, x, y));
//        tike.calcDelta();
//        tike.addDeltaToIso(ga.globals.isoMap);
//        ga.globals.setCurNode(tike);
//
//        if ( ! ga.globals.editMode ) {
//            // . . . these aren't the moves you're looking for . . . move along . . .
//            // off tree means wrong
//            // we punish deviants
//            ga.setResult(Intersection.WRONG, true);
//        }
//
//        // add a baby for current node
//        addChild(tike);
//
//        ga.atlas.calculatePositions(ga.globals.tree);
//        ga.atlas.repaint();
    }

    /**
     * a move here means what result for the player?
     */
    void setResultFromMovingHere() {
//        if (result == Intersection.RIGHT)
//            ga.setResult(Intersection.RIGHT, true);
//        else if (result == Intersection.WRONG)
//            ga.setResult(Intersection.WRONG, true);
//        else if (!searchForTheTruth()) {
//            // if they have no children, then the setresult should display wrong,
//            // otherwise, let them keep wading down
//            // this path of disillusionment and despair
//            ga.setResult(Intersection.WRONG, babies.size() == 0 || !((Node) babies.firstElement()).originalPath );
//        }
    }
    
    // start at top of tree (this), descend until hit a move
    // execute commands along the way -- useful for setting up board etc.
    public Node advance2move() {
        Node node = this, last = this;
        while (node.hasMove == false) {
            last = node;
            node = (Node) node.babies.firstElement();
        }
        
        // back up one to the node before the move
        node = last;
        return node;
    }

    // tests if it is on board, but also legal capturing etc.
    public boolean legalMove(Point op) {
        // test board
        try {
            Board nb = (Board) board.clone();
            return nb.makeMove(op.x, op.y, getToMove());
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    // find node closest to where we just clicked
    class DepthCalcRecurser extends NodeRecurser {
        public DepthCalcRecurser() {
        }
        public void action(Node n) {
            if (n.mom != null) {
                n.depth = n.mom.depth + 1;
            }
        }
    }
    
    /**
     * combine initial nodes that have no move
     * @return base
     */
    public Node collapsePrelude() {
        Node n = this;
        while (n.babies.size() > 0) {
            Node babe = (Node) n.babies.firstElement();
            if (babe.hasMove)
                break; // we've gone far enough
            
            // we need to move our instructions into our baby's
            for (int i = 0; i < n.acts.size(); i++)
                babe.addAct((Action) n.acts.elementAt(i));
            // also move our xtra tag stuff
            for (int j = 0; j < n.xtraTags.size(); j++) {
                babe.xtraTags.addElement(n.xtraTags.elementAt(j));
                babe.xtraTagVals.addElement(n.xtraTagVals.elementAt(j));
            }
            
            babe.mom = null; // effectively shorten the tree from the bottom
            n = babe; // keep going down...
        }
        
        // if we've redacted, we need to update depths
        n.depth = 0;
        
        DepthCalcRecurser depthRec = new DepthCalcRecurser();
        n.generalRecurse(depthRec);
        
        return n;
    }
    
    // figger out who is about to move from this node
    public int getToMove() {
        for (Enumeration be = babies.elements(); be.hasMoreElements();) {
            Node node = (Node) be.nextElement();
            for (Enumeration e = node.acts.elements(); e.hasMoreElements();) {
                Action a = (Action) e.nextElement();
                if (a.getMove() != null)
                    return (((MoveAction) a).stone);
            }
        }
        return defaultToMoveColor;
    }

    public String outputSGF() {
    	return outputSGF(false);
    }
    
    /** prints sequence of moves to this point */
    public String printPath2Here() {
    	Node n = this;
    	String s = "";
    	while (n != null) {
    		MoveAction ma = n.getMoveAction();
    		if (ma != null) {
    			s = ma.toString() + " " + s;
    		}
    		n = n.mom;
    	}
    	return s;
    }
    
    public String outputSGF(boolean createSetup) {
        String s = ";"; // always start with a semicolon. that's just the way it is

        // now add all the actions of our node
        boolean hitComment = false;
        String comXtra = null;
        if (isChoice || forceMove || notThis || (result == Intersection.RIGHT) || (result == Intersection.WRONG)) {
            comXtra = "";
            if (isChoice)
                comXtra = "CHOICE";
            if (forceMove)
                comXtra += "FORCE";
            if (notThis)
                comXtra += "NOTTHIS";
            if (result == Intersection.RIGHT)
                comXtra += "RIGHT";
            if (result == Intersection.WRONG)
                comXtra += "WRONG";
        }
        for (Enumeration e = acts.elements(); e.hasMoreElements();) {
            Action a = (Action) e.nextElement();
            s += a.outputSGF();
            // we have to do special stuff for comment node
            if (comXtra != null && (a instanceof CommentAction)) {
                hitComment = true;
                // insert xtra
                s = s.substring(0, s.length() - 1) + comXtra + "]";
            }
        }

        // add setup nodes from board
        if (createSetup) {
    		var initB = new ArrayList<String>();
    		var initW = new ArrayList<String>();
            for (int i = 0; i < 19; i++)
                for (int j = 0; j < 19; j++) {
                    Intersection insec = board.board[i][j];
                    if (insec.stone == Intersection.BLACK) {
                    	initB.add(Intersection.toSGFcoord(i, j));
                    }
                    else if (insec.stone == Intersection.WHITE) {
                    	initW.add(Intersection.toSGFcoord(i, j));
                    }
                }
        	if (initB.size() > 0) {
        		s += "AB";
        		for (String spos: initB) {
        			s += "[" + spos + "]";
        		}
        	}
        	if (initW.size() > 0) {
        		s += "AW";
        		for (String spos: initW) {
        			s += "[" + spos + "]";
        		}
        	}
        }

        // if we didn't hit a comment action, we need to insert one if there's XTRA
        if (comXtra != null && hitComment == false) {
            s += "C[" + comXtra + "]";
        }

        // add any xtra tag stuff we took from SGF but didn't use
        for (int i = 0; i < xtraTags.size(); i++) {
            s += ((String) xtraTags.elementAt(i)) + "[" + ((String) xtraTagVals.elementAt(i)) + "]";
        }

        // and add from babies
        if (babies.size() == 1) {
            Node babe = (Node) (babies.firstElement());
            if (babe.nodeType != Node.TYPE_COMMENT)
                s += (babe).outputSGF();
        }
        else if (babies.size() > 1) {
            for (Enumeration be = babies.elements(); be.hasMoreElements();) {
                Node node = (Node) be.nextElement();
                if (node.nodeType != Node.TYPE_COMMENT)
                    s += "\n(" + node.outputSGF() + ")";
            }
        }

        return s;
    }
    
    /**
     * return true if this could be a valid problem: there's a solution that can be reached
     */
    public boolean validSGF() {
        if (searchForTheTruth())
            return true;
        return false;    
    }

    /**
     * return a baby if we have one, whichever we last hugged
     * 
     * @return last Node visited
     */
    public Node favoriteSon() {
        if (babies.size() == 0)
            return null; // Fonda: End of the line. Bronson: ... yes.
        Node best = null;
        for (Enumeration e = babies.elements(); e.hasMoreElements();) {
            Node n = (Node) e.nextElement();
            if (best == null)
                best = n;
            if (n.chosenPath)
                best = n;
        }
        return best;
    }

    /**
     * move between different choices
     */
    public void moveChosenSon(int dir) {
        if (babies.size() < 2)
            return; // no point
        
        int pos = 0;
        for (int i = 0; i < babies.size(); i++) {
            Node n = (Node) babies.elementAt(i);
            if (n.chosenPath) {
                pos = i;
                n.chosenPath = false;
            }
        }

        pos = (pos + dir + babies.size()) % babies.size();
        ((Node) babies.elementAt(pos)).chosenPath = true;
    }
    
    /**
     * link back our favored sons
     */
    public void setTracePath() {
        Node cur = this;
        Node next = mom;
        while (next != null) {
            for (Enumeration e = next.babies.elements(); e.hasMoreElements();) {
                Node node = (Node) e.nextElement();
                node.chosenPath = (node == cur); // only we are special
            }
            // advance
            cur = next;
            next = next.mom;
        }
    }

    /**
     * @return true if this node is on the current nav path
     */
    public boolean onChosenPath(Node target) {
        if (target == null)
            return false;
        if (target == this)
            return true; // identity
        
        // chase upwards
        Node cur = this;
        Node next = mom;
        while (next != null) {
            if (cur.chosenPath == false && next.babies.size() > 1) {
                // sometimes there is no chosen child, in which case the first is it
                boolean defaultChild = true;
                if (next.babies.elementAt(0) != cur)
                    defaultChild = false;
                else {
                    for (Enumeration e = next.babies.elements(); e.hasMoreElements();) {
                        Node node = (Node) e.nextElement();
                        if (node.chosenPath) defaultChild = false;
                    }                    
                }
                if (!defaultChild)
                    return false;
            }
            
            // checkfor goal
            if (next == target)
                return true;
            
            // advance
            cur = next;
            next = next.mom;
        }
        
        // look in future
        FindSpecificNodeRecurser fsn = new FindSpecificNodeRecurser(target);
        generalRecurse(fsn);
        if (fsn.found)
            return true;

        return false;
    }

    // put colors for move possiblities -- used in navigate solution
    public void markCrayons() {
        for (int i = 0; i < 19; i++)
            for (int j = 0; j < 19; j++)
                board.board[i][j].setColor(null); // clear existing

        for (Enumeration e = babies.elements(); e.hasMoreElements();) {
            Node n = (Node) e.nextElement();
            MoveAction ma = n.getMoveAction();
            if (ma != null) {
                Point p = ma.loc;
                if (p != null && p.x >= 0 && p.x < 19 && p.y >= 0 && p.y < 19) {
                    Color c = Color.green;
                    if (n.searchForTheTruth() == false) {
                        c = Color.red;
//                        if (!n.originalPath)
//                            c = Pix.colFreshWrongPath;
                    }
                    board.board[p.x][p.y].setColor(c);
                }
            }
        }
    }
    
    /** return true if this move exists */
    public boolean hasMove(Point move) {
        for (Enumeration e = babies.elements(); e.hasMoreElements();) {
            Node n = (Node) e.nextElement();
            MoveAction ma = n.getMoveAction();
            if (ma != null) {
                Point p = ma.loc;
                if (p.x == move.x && p.y == move.y)
                	return true;
            }
        }
        return false;
    }

    public BoardDelta getDelta() {
        return delta;
    }

    public String toString() {
        Point p = findMove();
        if (p == null) return "root";
        return Intersection.toGTPloc(p.x, p.y, board.boardY);
    }

    public void removeChildNode(Node del) {
        babies.remove(del);
        markCrayons();
    }

    public void removeAllChildren() {
        babies.removeAllElements();
        markCrayons();
    }

    public void incrementPathCount() {
        pathCount++;
    }

    public int getPathCount() {
        return pathCount;
    }

    public Point getKo() {
        if (board.deathList.size() != 1)
            return null;
        Point p = findMove();
        if (p == null) return null;
        if (board.isKo(p))
            return (Point) board.deathList.elementAt(0);
        return null;
    }
}
