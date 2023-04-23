package autoprob.go.action;

import java.awt.Point;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;

/**
 * corresponds to AW or AB in SGF
 * 
 * @author amiller
 */
public class SetupAction extends Action {
    public Point loc = new Point(-1, -1);
    public int   stone;

    public SetupAction(int instone, int x, int y) {
        stone = instone;
        loc.x = x;
        loc.y = y;
    }

    public SetupAction(String src, int instone, Node node) {
        if (src.length() != 2) {
            System.out.println("Error in setup: not two chars long: " + src);
            return;
        }
        stone = instone;
        loc.x = src.charAt(0) - 'a';
        loc.y = src.charAt(1) - 'a';
    }

    public void execute(Board board) {
        if (!board.inBoard(loc.x, loc.y)) {
            System.out.println("Error in move: off board: " + loc.x + " " + loc.y);
            return;
        }
        board.board[loc.x][loc.y].setStone(stone);
    }

    public String outputSGF() {
        String s;
        if (stone == Intersection.BLACK)
            s = "AB[";
        else
            s = "AW[";
        return s + loc2string(loc) + "]";
    }
}
