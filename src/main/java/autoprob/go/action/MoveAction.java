package autoprob.go.action;

// a move

import java.awt.Point;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;

public class MoveAction extends Action {
    public Point loc = new Point(-1, -1);
    public int   stone;

    public MoveAction(int stone, Node node, int x, int y) {
        this.stone = stone;
        loc.x = x;
        loc.y = y;
        node.hasMove = true;
    }

    public MoveAction(String src, int instone, Node node) {
        if (src.length() != 2) {
            System.out.println("Error in move: not two chars long, will treat as pass: '" + src + "'");
            return;
        }
        stone = instone;
        loc.x = src.charAt(0) - 'a';
        loc.y = src.charAt(1) - 'a';
        /*
         * if (!goban.inBoard(loc.x, loc.y)) { System.out.println("Error in
         * move: off board: " + src); loc = null; return; }
         */
        node.hasMove = true;
    }

    public Point getMove() {
        return loc;
    }

    public void execute(Board board) {
    	if (loc.x == 19 || loc.y == 19)
    		return; // pass
        if (!board.inBoard(loc.x, loc.y)) {
            // it's a pass
            return;
        }
        board.makeMove(loc.x, loc.y, stone);
    }

    public String outputSGF() {
        String s;
        if (stone == Intersection.BLACK)
            s = "B[";
        else
            s = "W[";
        return s + loc2string(loc) + "]";
    }
    
    @Override
    public String toString() {
    	return Intersection.toGTPloc(loc.x, loc.y, 19);
    }
}
