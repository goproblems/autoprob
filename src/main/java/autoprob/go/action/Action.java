package autoprob.go.action;

// base class for different game actions (from SGF, typically)

import java.awt.Point;

import autoprob.go.Board;

public abstract class Action {
    public Action() {
    }

    // operate this action on current board state
    public void execute(Board board) {
    }

    // location on board, if appropriate
    public Point getMove() {
        return null;
    }

    // return this expressed in SGF text
    public String outputSGF() {
        return "";
    }

    protected String loc2string(Point loc) {
        String s = (new Character((char) ('a' + loc.x))).toString();
        return s + ((char) ('a' + loc.y));
    }
}
