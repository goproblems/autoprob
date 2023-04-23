package autoprob.go.action;

import java.awt.Point;

import autoprob.go.Board;
import autoprob.go.Node;

/**
 * a certain location is labeled with a string (usually one char)
 * @author amiller
 */
public class LabelAction extends Action {
    public Point loc = new Point(-1, -1);
    String       label;

    public LabelAction(String label, int x, int y) {
        loc.x = x;
        loc.y = y;
        this.label = "" + label;
    }

    public LabelAction(String src, Node node) {
        if (src.length() < 4) {
            System.out.println("Error in LB: not long enough: " + src);
            return;
        }
        loc.x = src.charAt(0) - 'a';
        loc.y = src.charAt(1) - 'a';
        label = src.substring(3, src.length());
    }

    public void execute(Board board) {
        if (!board.inBoard(loc.x, loc.y))
            return;
        board.board[loc.x][loc.y].setText(label);
    }

    public String outputSGF() {
        return "LB[" + loc2string(loc) + ":" + label + "]";
    }
}
