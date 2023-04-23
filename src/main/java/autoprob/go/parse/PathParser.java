package autoprob.go.parse;

import java.awt.Point;
import java.util.Enumeration;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.Vector;

import autoprob.go.Node;
import autoprob.go.action.MoveAction;

public class PathParser {
    
    public interface NewNodeVisitor {
        public void visitNewNode(Node n, boolean isLeaf);

        public void visitExistingNode(Node n, boolean isLeaf);
    }

    public static void parse(String solutionPaths, Node tree, NewNodeVisitor vis) {
        StringTokenizer st = new StringTokenizer(solutionPaths);
        while (st.hasMoreTokens()) {
            String line = st.nextToken();
            if (line.length() == 0)
                continue;
            // parse line
            Vector moves = new Vector();
            if (line.charAt(0) >= 'A') {
                // short format
                int n = line.length() / 2 * 2;
                for (int i = 0; i < n; i += 2) {
                    Point p = new Point(line.charAt(i) - 'A', line.charAt(i + 1) - 'a');
                    moves.addElement(p);
                }
            }
            else {
                // long format
                StringTokenizer lt = new StringTokenizer(line, "|");
                try {
                    while (lt.hasMoreTokens()) {
                        String move = lt.nextToken();
                        // parse move
                        StringTokenizer mt = new StringTokenizer(move, ".");
                        String locx = mt.nextToken();
                        String locy = mt.nextToken();
                        // add point
                        Point p = new Point(Integer.parseInt(locx), Integer.parseInt(locy));
                        moves.addElement(p);
                    }
                }
                catch (NoSuchElementException e) {
                    System.out.println("bad loc: " + e);
                    continue;
                }
            }
            
            addMoveSequence(moves, tree, vis);
        }
    }

    private static void addMoveSequence(Vector moves, Node tree, NewNodeVisitor vis) {
        Node n = tree;
        loop: for (int i = 0; i < moves.size(); i++) {
            Point p = (Point) moves.elementAt(i);
            // find baby of this move
            for (Enumeration e = n.babies.elements(); e.hasMoreElements();) {
                Node child = (Node) e.nextElement();
                Point m = child.findMove();
                if (m == null) continue;
                if (!m.equals(p)) continue;
                // found it
                n = child;
                vis.visitExistingNode(n, i == moves.size() - 1);
                continue loop;
            }
            // we didn't find a match, so add the node manually
            Node nx = new Node(n);
            nx.addAct(new MoveAction(n.getToMove(), nx, p.x, p.y));
            vis.visitNewNode(nx, i == moves.size() - 1);
            n.babies.add(nx);
            n = nx;
        }
    }
}
