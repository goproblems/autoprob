package autoprob.go.vis;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.util.Enumeration;

import javax.swing.JComponent;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.NodeChangeListener;
import autoprob.go.action.Action;
import autoprob.go.action.LabelAction;
import autoprob.go.action.SetupAction;
import autoprob.go.action.SquareAction;
import autoprob.go.action.TriangleAction;
import autoprob.go.parse.ViewableRecurser;

public class BasicGoban extends JComponent implements MouseListener, MouseMotionListener, KeyListener, NodeChangeListener {
	/** how big are the squares */
    int                     sz              = 32;
    static final boolean    THICKEDGES      = true;
    static final int        SHADOWSIZE      = 4;

    /** time to fade out capped stones */
    public static final int FADE_OUT_MILLIS = 300;
//    boolean                 shiftDown       = false, capsDown = false;
    int                     starsz          = 6;
    int                     lastmsz         = 10;
    boolean                 wantSmall       = false;

    // following two for making nice smooth graphics
    protected Image         offImage        = null;
    private Graphics        offscreen       = null;

    int                     mx              = 0, my = 0;                // mouse
    // positions

    // what part of the board we're seeing
    public Rectangle        viewable        = new Rectangle(0, 0, 19, 19);
    /** where the ghost stone is (player hovering) */
    Point                   ghost           = new Point(-1, -1);
    /** the last move gets marked */
    // Point lastMove = new Point(-1, -1);
    /**
     * used to communicate that we have to erase the background -- when changing
     * stone sizes
     */
    public boolean          fullDraw        = false;
    protected Node node;

    // constructor
    public BasicGoban(Node node) {
    	this.node = node;
    	
        // layout
        setBackground(Pix.colBack);

        addMouseMotionListener(this);
        addMouseListener(this);
        addKeyListener(this);
    }

    public Dimension getMinumumSize() {
        int x = 16 * (19 + 2) + SHADOWSIZE;
        return new Dimension(x, x);
    }

    public Dimension getPreferredSize() {
        Dimension d = new Dimension(32 * (Math.max(0, viewable.width) + 2) + SHADOWSIZE, 32
                * (Math.max(0, viewable.height) + 2) + SHADOWSIZE);
        return d;
    }

    public void goSmall() {
        sz = 16;
        offImage = createImage(sz, sz);
        offscreen = offImage.getGraphics();
        offscreen.setFont(new Font("Monospaced", Font.PLAIN, 12));
        starsz = 6;
        lastmsz = 6;
        Pix.useSmallImages();
        fullDraw = true;
        repaint();
    }

    public void goLarge() {
        sz = 32;
        offImage = createImage(sz, sz);
        offscreen = offImage.getGraphics();
        offscreen.setFont(new Font("Monospaced", Font.BOLD, 14));
        starsz = 6;
        lastmsz = 10;
        Pix.useBigImages();
        repaint();
    }

    public void goFit() {
        Dimension dim = getSize();
        int szx = (dim.width - SHADOWSIZE) / (Math.max(0, viewable.width) + 2);
        int szy = (dim.height - SHADOWSIZE) / (Math.max(0, viewable.height) + 2);
        sz = Math.min(szx, szy);
        offImage = createImage(sz, sz);
        offscreen = offImage.getGraphics();
        offscreen.setFont(new Font("Monospaced", Font.BOLD, sz < 32 ? 12 : 14));
        starsz = 6;
        lastmsz = 6;
        //TODO
//        Pix.useFitImages(ga, sz);
        repaint();
    }

    // how much of the board do we need to show for this solution?
    public void calcViewable() {
        // remember, bottom right is one _past_ last intersection
        // first examine initial board position
        int minx = 19, miny = 19, maxx = 0, maxy = 0;
        for (int i = 0; i < 19; i++)
            for (int j = 0; j < 19; j++)
                if (node.board.board[i][j].stone != Intersection.EMPTY) {
                    if (i > maxx)
                        maxx = i;
                    if (i < minx)
                        minx = i;
                    if (j > maxy)
                        maxy = j;
                    if (j < miny)
                        miny = j;
                }

        // now look through moves
        ViewableRecurser vr = new ViewableRecurser(minx, maxx, miny, maxy);
        node.generalRecurse(vr);
        // get values back
        minx = vr.minx;
        miny = vr.miny;
        maxx = vr.maxx;
        maxy = vr.maxy;

        Board brd = node.board;

        // expand to edges of board if close
        if (miny <= 3)
            miny = 0;
        if (maxy >= brd.boardY - 4)
            maxy = brd.boardY - 1;
        if (minx <= 3)
            minx = 0;
        if (maxx >= brd.boardX - 4)
            maxx = brd.boardX - 1;
        miny--;
        minx--;
        maxy += 2;
        maxx += 2;
        // check board limits
        if (miny <= 0)
            miny = 0;
        if (maxy >= brd.boardY)
            maxy = brd.boardY;
        if (minx <= 0)
            minx = 0;
        if (maxx >= brd.boardX)
            maxx = brd.boardX;
        viewable = new Rectangle(minx, miny, maxx - minx, maxy - miny);
    }

    void centerSquareText(String s) {
        int fx = sz / 2, fy = sz / 2;
        FontMetrics fm = getFontMetrics(offscreen.getFont());
        fx -= fm.stringWidth(s) / 2;
        fy += (fm.getAscent()) / 2;
        // put it on the temp image
        offscreen.drawString(s, fx, fy);
    }

    // test if a spot is a valid move
    boolean validMove(int x, int y) {
        Intersection inter = node.board.board[x][y];
        int stone = inter.stone;
        if (node.getToMove() == Intersection.EMPTY || stone != Intersection.EMPTY)
            return false;
        
        // cannot retake ko immediately
        Point ko = node.getKo();
        if (ko != null && ko.x == x && ko.y == y)
            return false;

        // if this position is a force, make sure we're over a choice
        boolean haveMove = false;
        for (Enumeration e = node.babies.elements(); e.hasMoreElements();) {
            Node n = (Node) e.nextElement();
            Point p = n.findMove();
            if (p != null && p.x == x && p.y == y) {
                if (n.nodeType != Node.TYPE_COMMENT)
                    haveMove = true;
                if (n.notThis)
                    return false;
            }
        }
        if (node.forceMove) {
            return haveMove;
        }

        return true;
    }

    // return true if legal move
    public boolean makeMove(int x, int y, int stone) {
        Board brd = node.board;
        if (!brd.inBoard(x, y))
            return false;

        if (!validMove(x, y))
            return false;
        try {
//        	node.handleMove(x, y, ga);
        }
        catch (Exception e) {
            return false;
        }
        return true;
    }

    // draw one square of a board, which in reality looks like a plus sign since
    // go works on intersections
    void drawSquare(int x, int y) {
    }

    public void mouseMoved(MouseEvent e) {
        e.consume();
        mx = e.getX();
        my = e.getY();
        int ox = ghost.x, oy = ghost.y;
        toSquare(mx, my, ghost);
//        if (ga.moveDelay == null) // don't show if waiting for move response
//            if (ghost.x != ox || ghost.y != oy)
        if (ox != ghost.x || oy != ghost.y)
        	mouseEnterSquare(ghost.x, ghost.y);
        
        repaint();
                
    }

    protected void mouseEnterSquare(int x, int y) {
	}

	public void mouseDragged(MouseEvent e) {
        e.consume();
        mx = e.getX();
        my = e.getY();
    }

    // translates screen coords into board coords, -1 as x value if not on board
    Point toSquare(int x, int y) {
        Point p = new Point();
        toSquare(x, y, p);
        return p;
    }

    void toSquare(int x, int y, Point p) {
        x -= sz;
        y -= sz;
        if (x < 0 || y < 0) {
            p.x = -1;
            return;
        }
        x /= sz;
        y /= sz;
        if (x >= viewable.width || y >= viewable.height) {
            p.x = -1;
            return;
        }
        x += viewable.x;
        y += viewable.y;
        p.x = x;
        p.y = y;
    }

    // trying to setup on this square
    private void doSetupClick(Point p) {
        Board brd = node.board;
        // if there's a stone here already, remove it
        // look at setup actions in this node
        for (Enumeration e = node.acts.elements(); e.hasMoreElements();) {
            Action a = (Action) e.nextElement();
            if (a instanceof SetupAction) {
                // found you!
                SetupAction sa = (SetupAction) a;
                if (!p.equals(sa.loc))
                    continue; // wrong place
                brd.board[p.x][p.y].stone = 0; // clear board
                node.acts.removeElement(a);
                postSetup(node);
                return;
            }
        }

        // fine, let's place a new stone
//        SetupAction sa = new SetupAction((glob.shiftDown /*^ capsDown*/) ? Globals.WHITE : Globals.BLACK, p.x, p.y);
        SetupAction sa = new SetupAction(Intersection.WHITE, p.x, p.y);
        node.acts.addElement(sa);
        brd.board[p.x][p.y].stone = sa.stone; // set board
        postSetup(node);
    }

    /**
     * we've done a setup action on this node. we should change the nodes below
     * us to reflect this modification
     * 
     * @param root
     */
    private void postSetup(final Node root) {
//        glob.updateNode(root);
//        // other nodes must change
//        root.generalRecurse(new NodeRecurser() {
//            public void action(Node n) {
//                if (n == root)
//                    return;
//
//                // copy board in from mom, then do our actions
//                try {
//                    n.board = (Board) n.mom.board.clone();
//                    n.board.removeMarkup();
//                }
//                catch (CloneNotSupportedException e) {
//                }
//                for (Enumeration e = n.acts.elements(); e.hasMoreElements();) {
//                    Action a = (Action) e.nextElement();
//                    a.execute(n.board);
//                }
//
//                // remove old delta from map
//                glob.isoMap.remove(n.getDelta(), n);
//                // calc new one and add it in
//                n.calcDelta();
//                n.addDeltaToIso(glob.isoMap);
//            }
//        });
    }

    // trying to triangle on this square
    private void doTriangleClick(Point p) {
        Board brd = node.board;
        // if there's a triangle here already, remove it
        // look at triangle actions in this node
        for (Enumeration e = node.acts.elements(); e.hasMoreElements();) {
            Action a = (Action) e.nextElement();
            if (a instanceof TriangleAction) {
                // found you!
                TriangleAction tr = (TriangleAction) a;
                if (!p.equals(tr.loc))
                    continue; // wrong place
                brd.board[p.x][p.y].setMarkup(Intersection.MARK_NONE); // clear
                // markup
                node.acts.removeElement(a);
                repaint();
                return;
            }
        }

        // fine, let's place a new stone
        TriangleAction tr = new TriangleAction(p.x, p.y);
        node.acts.addElement(tr);
        tr.execute(brd); // set board
        repaint();
    }

    // trying to square on this square
    void doSquareClick(Point p) {
        Board brd = node.board;
        // if there's a square here already, remove it
        // look at square actions in this node
        for (Enumeration e = node.acts.elements(); e.hasMoreElements();) {
            Action a = (Action) e.nextElement();
            // take advantage of square being a subclass of triangle
            if (a instanceof TriangleAction) {
                // found you!
                TriangleAction tr = (TriangleAction) a;
                if (!p.equals(tr.loc))
                    continue; // wrong place
                brd.board[p.x][p.y].setMarkup(Intersection.MARK_NONE); // clear
                // markup
                node.acts.removeElement(a);
                repaint();
                return;
            }
        }

        // fine, let's place a new stone
        SquareAction sa = new SquareAction(p.x, p.y);
        node.acts.addElement(sa);
        sa.execute(brd); // set board
        repaint();
    }

    // trying to label on this square
    void doLabelClick(Point p) {
        Board brd = node.board;
        // if there's a label here already, edit
        // look at label actions in this node
        for (Enumeration e = node.acts.elements(); e.hasMoreElements();) {
            Action a = (Action) e.nextElement();
            // take advantage of square being a subclass of triangle
            if (a instanceof LabelAction) {
                // found you!
                LabelAction la = (LabelAction) a;
                if (!p.equals(la.loc))
                    continue; // wrong place
                brd.board[p.x][p.y].setText(null); // clear label
                node.acts.removeElement(a);
                repaint();
                return;
            }
        }
    }
    
    public void clickSquare(Point p, MouseEvent e) {
    	System.out.println("click: " + p);
//    	System.out.println(e);
    }

    public void mouseReleased(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        Point p = toSquare(x, y);
        if (p.x < 0)
            return; // not a square
        clickSquare(p, e);
//        if (ga.moveDelay == null) { // don't do it if waiting for move response
//            if (glob.editMode) {
//                CheckboxGroup cbg = ga.controlPanel.editControlPanelOpts.editCheckboxGroup;
//                Checkbox cur = cbg.getSelectedCheckbox();
//                EditOptionsPanel ep = ga.controlPanel.editControlPanelOpts;
//                if (cur == ep.ebBlack || cur == ep.ebWhite) {
//                    // trying to make a move
//                    makeMove(p.x, p.y, glob.getToMove());
//                }
//                else if (cur == ep.ebSetup)
//                    doSetupClick(p);
//                else if (cur == ep.ebTriangle)
//                    doTriangleClick(p);
//                else if (cur == ep.ebSquare)
//                    doSquareClick(p);
//                else if (cur == ep.ebLabel)
//                    doLabelClick(p);
//                return;
//            }
//            makeMove(p.x, p.y, glob.getToMove());
//        }
    }

    public void mousePressed(MouseEvent e) {
    }

    public void mouseClicked(MouseEvent e) {
    }

    public void mouseEntered(MouseEvent e) {
    }

    public void mouseExited(MouseEvent e) {
        ghost = new Point(-1, -1);
        repaint();
    }

    public void keyPressed(KeyEvent ev) {
//        if (ev.getKeyCode() == KeyEvent.VK_CAPS_LOCK) {
//            capsDown = true;
//            repaint();
//        }
//
//        if (ev.getKeyCode() == KeyEvent.VK_SHIFT) {
//            shiftDown = true;
//            repaint();
//        }
    }

    public void keyReleased(KeyEvent ev) {
//        // System.out.println("key rel: " + ev.getKeyChar());
//        if (ev.getKeyCode() == KeyEvent.VK_CAPS_LOCK) {
//            capsDown = false;
//            repaint();
//        }
//
//        if (ev.getKeyCode() == KeyEvent.VK_SHIFT) {
//            shiftDown = false;
//            repaint();
//            // System.out.println("shift up");
//        }
    }

    public void keyTyped(KeyEvent ev) {
        // System.out.println("key tyo: " + ev.getKeyChar());
    }

    public void newCurrentNode(Node node) {
        repaint();
        // animate killing
        if (node.board.isKilled())
            new RefreshThread(this, FADE_OUT_MILLIS).start();
    }

    public void nodeChanged(Node node) {
        repaint();
    }
}
