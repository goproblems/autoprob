package autoprob.go.action;

import autoprob.go.Board;
import autoprob.go.Node;

// board size
public class SizeAction extends Action {
    int boardSize = 19;

    public SizeAction(String src, Node node) {
        boardSize = Integer.parseInt(src);
    }

    public SizeAction(int size) {
        boardSize = size;
    }

    public void execute(Board board) {
        board.boardX = boardSize;
        board.boardY = boardSize;
    }

    public String outputSGF() {
        return "SZ[" + boardSize + "]";
    }
}
