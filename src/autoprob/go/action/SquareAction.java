package autoprob.go.action;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;

// a certain location is marked with a Square
public class SquareAction extends TriangleAction {

    public SquareAction(int x, int y) {
        super(x, y);
    }

    public SquareAction(String src, Node node) {
        super(src, node);
    }

    public void execute(Board board) {
        if (!board.inBoard(loc.x, loc.y))
            return;
        board.board[loc.x][loc.y].setMarkup(Intersection.MARK_SQUARE);
    }

    public String outputSGF() {
        return "MA[" + loc2string(loc) + "]";
    }
}
