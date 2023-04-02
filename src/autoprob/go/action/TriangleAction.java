package autoprob.go.action;

import java.awt.Point;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;

/**
 * a certain location is marked with a triangle
 * @author amiller
 */
public class TriangleAction extends Action {
    public Point loc = new Point(-1, -1);

    public TriangleAction(int x, int y) {
        loc.x = x;
        loc.y = y;
    }

    public TriangleAction(String src, Node node) {
        if (src.length() != 2) {
            System.out.println("Error in TR: not two chars long: " + src);
            return;
        }
        loc.x = src.charAt(0) - 'a';
        loc.y = src.charAt(1) - 'a';
    }

    public void execute(Board board) {
        if (!board.inBoard(loc.x, loc.y))
            return;
        board.board[loc.x][loc.y].setMarkup(Intersection.MARK_TRIANGLE);
    }

    public String outputSGF() {
        return "TR[" + loc2string(loc) + "]";
    }
}
