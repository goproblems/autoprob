package autoprob.go.action;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;

import java.awt.*;

public class CircleAction extends Action {
    public Point loc = new Point(-1, -1);

    public CircleAction(int x, int y) {
        loc.x = x;
        loc.y = y;
    }

    public CircleAction(String src, Node node) {
        if (src.length() != 2) {
            System.out.println("Error in CR: not two chars long: " + src);
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
        return "CR[" + loc2string(loc) + "]";
    }
}
