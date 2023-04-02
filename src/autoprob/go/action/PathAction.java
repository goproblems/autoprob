package autoprob.go.action;

import java.awt.Color;
import java.awt.Point;

import autoprob.go.Board;

/**
 * a certain location is labeled with a string
 * 
 * @author amiller
 */
public class PathAction extends Action {
    public Point loc;
    int          count = 1;

    public PathAction(Point loc) {
        this.loc = loc;
    }

    public void increment() {
        count++;
    }

    public void execute(Board board) {
        if (!board.inBoard(loc.x, loc.y))
            return;
        board.board[loc.x][loc.y].setText(Integer.toString(count));
        board.board[loc.x][loc.y].setColor(Color.blue);
    }
}
