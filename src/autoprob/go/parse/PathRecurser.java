package autoprob.go.parse;

import java.awt.Point;
import java.util.Enumeration;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.Vector;

import autoprob.go.Node;
import autoprob.go.action.Action;
import autoprob.go.action.MoveAction;
import autoprob.go.action.PathAction;

// marks up a node for how many paths passed by
public class PathRecurser extends NodeRecurser {
    Vector paths = new Vector();

    public PathRecurser(String pathString) {
        StringTokenizer st = new StringTokenizer(pathString);
        while (st.hasMoreTokens()) {
            String line = st.nextToken();
            if (line.length() == 0)
                continue;
            // parse line
            Vector moves = new Vector();
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
            // add moves vector to paths vector
            paths.addElement(moves);
        }
    }

    public void action(Node n) {
        // trace back through ancestors to create move list
        Vector sgfMoves = new Vector();
        Node anc = n;
        while (anc != null) {
            MoveAction ma = anc.getMoveAction();
            if (ma != null)
                sgfMoves.addElement(ma.loc);
            anc = anc.mom;
        }

        int depth = sgfMoves.size();

        // now loop through paths to see who matches
        for (Enumeration e = paths.elements(); e.hasMoreElements();) {
            Vector pathMoves = (Vector) e.nextElement();
            // make sure big enough -- there must be one more move than depth
            // cuz we need to show future
            if (pathMoves.size() < depth + 1)
                continue;

            try {
                // loop through depth moves to see if all match
                for (int i = 0; i < depth; i++) {
                    Point pathPoint = (Point) pathMoves.elementAt(i);
                    Point nodePoint = (Point) sgfMoves.elementAt(depth - (i + 1));
                    if (pathPoint.equals(nodePoint) == false)
                        throw new Exception();
                }

            }
            catch (Exception excep) {
                continue;
            }

            // we've found a match
            // now, let's mark up what follows

            // what's the point we're trying to mark?
            Point nextMove = (Point) pathMoves.elementAt(depth);

            // search for an existing PathAction
            boolean found = false;
            for (Enumeration eact = n.acts.elements(); eact.hasMoreElements();) {
                Action a = (Action) eact.nextElement();
                if (a instanceof PathAction) {
                    PathAction pth = (PathAction) a;
                    // make sure it's on the same spot -- can have multiple in one node
                    if (pth.loc.equals(nextMove)) {
                        pth.increment();
                        found = true;
                    }
                }
            }

            if (!found) {
                // we need to add our own PathAction
                PathAction pa = new PathAction(nextMove);
                n.acts.addElement(pa);
            }
        }
    }
}
