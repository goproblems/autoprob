package autoprob.go;

import autoprob.katastruct.KataAnalysisResult;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class StoneGrouping {
    int[][] offsets = {{-1, 0}, {0, 1}, {1, 0}, {0, -1}};

    public boolean isOnboard(int x, int y) {
        return !(x < 0 || y < 0 || x >= 19 || y >= 19);
    }

    private void floodRecurse(int x, int y, Board board, boolean[][] fill, StoneGroup sg) {
        if (fill[x][y]) return;
        if (board.board[x][y].stone != sg.stone) return;

        // new point in group
        fill[x][y] = true;
        sg.stones.add(new Point(x, y));

        for (int i = 0; i < 4; i++) {
            int nx = x + offsets[i][0];
            int ny = y + offsets[i][1];
            if (!isOnboard(nx, ny)) continue;
            floodRecurse(nx, ny, board, fill, sg);
        }
    }

    // create a list of all separate groups on the board, along with ownership
    public List<StoneGroup> groupStones(Board board, KataAnalysisResult kar) {
        // make a 2d array of locations we've checked
        boolean[][] checked = new boolean[19][19];
        var groups = new ArrayList<StoneGroup>();
        // iterate through board
        for (int x = 0; x < 19; x++) {
            for (int y = 0; y < 19; y++) {
                if (checked[x][y]) continue;

                if (board.board[x][y].stone == Intersection.EMPTY) continue;

                // new group
                StoneGroup sg = new StoneGroup(board.board[x][y].stone);
                groups.add(sg);

                // figure out group members
                floodRecurse(x, y, board, checked, sg);
                sg.calculateOwnership(kar);
            }
        }
        return groups;
    }
}
