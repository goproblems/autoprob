package autoprob.go;

import autoprob.katastruct.KataAnalysisResult;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class StoneGroupLogic {
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

    // find where a group has gone to
    public StoneGroup findGroupAfterChange(StoneGroup sg, Board board, List<StoneGroup> postChangeGroups) {
        // iterate through groups, look for one where all the stones in the needle are in the haystack
        for (StoneGroup sg2: postChangeGroups) {
            boolean found = true;
            for (Point p: sg.stones) {
                if (!sg2.stones.contains(p)) {
                    found = false;
                    break;
                }
            }
            if (found) return sg2;
        }
    	return null;
    }

    // calculate a group delta
    public double groupDelta(StoneGroup sg, KataAnalysisResult kar) {
    	return sg.ownership - (sg.stone == Intersection.BLACK ? kar.ownership.get(0) : kar.ownership.get(1));
    }

    public static class PointCount {
        public int stone;
        public int count;
        public PointCount(int stone, int count) {
            this.stone = stone;
            this.count = count;
        }
    }

    // flood empty space from a corner, see if it's owned by one player
    private void floodRecurseOwnership(int x, int y, Board board, boolean[][] fill, PointCount pc, KataAnalysisResult kar, double minOwnership) {
        if (fill[x][y]) return;
        if (board.board[x][y].stone != Intersection.EMPTY) return;

        // tried this at this point
        fill[x][y] = true;

        double own = kar.ownership.get(x + y * 19);
        // first check magnitude
        if (Math.abs(own) < minOwnership) return;
        // now make sure it's aligned with original color
        if (own > 0 && pc.stone != Intersection.BLACK) return;

        // new point in group
        pc.count++;

        for (int i = 0; i < 4; i++) {
            int nx = x + offsets[i][0];
            int ny = y + offsets[i][1];
            if (!isOnboard(nx, ny)) continue;
            floodRecurseOwnership(nx, ny, board, fill, pc, kar, minOwnership);
        }
    }

    public PointCount countCornerPoints(Board board, KataAnalysisResult kar) {
        double minOwnership = 0.7; // must be at least this owned by someone
        // flood fill from corners. only one should have anything owned at most
        for (int startx = 0; startx < 19; startx += 18) {
            for (int starty = 0; starty < 19; starty += 18) {
                boolean[][] fill = new boolean[19][19];
                double own = kar.ownership.get(startx + starty * 19);
                PointCount pc = new PointCount(own > 0 ? Intersection.BLACK : Intersection.WHITE, 0);
                floodRecurseOwnership(startx, starty, board, fill, pc, kar, minOwnership);
                System.out.println("corner points: (" + startx + ", " + starty + ") : " + Intersection.color2name(pc.stone) + " = " + pc.count);
                if (pc.count > 0) return pc;
            }
        }
        return null;
    }

    // returns distance from each side, plus color of stone in 5th array index
    public int[] calcGroupExtents(Board board) {
        int[] extents = {19, 19, 19, 19, Intersection.EMPTY};
        int[] stonesHit = {0, 0, 0}; // accumulate stones hit on each side

        // shoot rays from each side of board on every line
        // int[][] offsets = {{-1, 0}, {0, 1}, {1, 0}, {0, -1}};
        for (int i = 0; i < 4; i++) {
            int dx = offsets[i][0];
            int dy = offsets[i][1];
            // start at edge depending on direction
            for (int line = 0; line < 19; line++) {
                int x = 0, y = 0;
                if (dx != 0) { // moving horizontally
                    y = line;
                    x = (dx == 1 ? 0 : 1) * 18;
                }
                if (dy != 0) { // moving vertically
                    x = line;
                    y = (dy == 1 ? 0 : 1) * 18;
                }
                // keep moving until we hit a stone or edge of board
                int distance = 0;
                while (isOnboard(x, y)) {
                    if (board.board[x][y].stone != Intersection.EMPTY) {
                        stonesHit[board.board[x][y].stone]++;
                        extents[i] = Math.min(extents[i], distance);
                        break;
                    }
                    x += dx;
                    y += dy;
                    distance++;
                }
            }
        }

        // set 5th index to color of stone that hit most sides
        if (stonesHit[Intersection.BLACK] > stonesHit[Intersection.WHITE]) {
            extents[4] = Intersection.BLACK;
        } else {
            extents[4] = Intersection.WHITE;
        }

        return extents;
    }

    public void drawWall(Board board, int startx, int starty, int endx, int endy, int stone) {
        int dx = endx - startx;
        int dy = endy - starty;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        for (int i = 0; i <= steps; i++) {
            int x = startx + i * dx / steps;
            int y = starty + i * dy / steps;
            if (isOnboard(x, y)) {
                board.board[x][y].stone = stone;
            }
        }
    }

    // finds the group, puts a wall around it at the specified gap
    public void buildFortress(Board board, int gap) {
        int[] extents = calcGroupExtents(board);
        int stone = extents[4];
        int opposite = stone == Intersection.BLACK ? Intersection.WHITE : Intersection.BLACK;
        // draw up to 4 walls around it at the specified gap
        int right = extents[0], left = extents[2], top = extents[1], bottom = extents[3];
        // put an extra wall behind the wall, opposite color
        drawWall(board, left - gap, top - gap, left - gap, 18 - bottom + gap, stone); // left wall
        drawWall(board, left - gap - 1, top - gap, left - gap - 1, 18 - bottom + gap, opposite); // left wall

        drawWall(board, left - gap, top - gap, 18 - right + gap, top - gap, stone); // top wall
        drawWall(board, left - gap, top - gap - 1, 18 - right + gap, top - gap - 1, opposite); // top wall

        drawWall(board, 18 - right + gap, top - gap, 18 - right + gap, 18 - bottom + gap, stone); // right wall
        drawWall(board, 18 - right + gap + 1, top - gap, 18 - right + gap + 1, 18 - bottom + gap, opposite); // right wall

        drawWall(board, left - gap, 18 - bottom + gap, 18 - right + gap, 18 - bottom + gap, stone); // bottom wall
        drawWall(board, left - gap, 18 - bottom + gap + 1, 18 - right + gap, 18 - bottom + gap + 1, opposite); // bottom wall

        // paint the outside of the walls checkerboard
        paintCheckerboard(board, opposite, 0, 18 - bottom + gap + 1, 18, 18); // bottom
        paintCheckerboard(board, opposite, 0, 0, 18, top - gap - 1); // top
        paintCheckerboard(board, opposite, 0, 0, left - gap - 1, 18); // left
        paintCheckerboard(board, opposite, 18 - right + gap + 1, 0, 18, 18); // right
    }

    private void paintCheckerboard(Board board, int stone, int startx, int starty, int endx, int endy) {
        for (int x = startx; x <= endx; x++) {
            for (int y = starty; y <= endy; y++) {
                if ((x + y) % 2 == 0) {
                    if (isOnboard(x, y)) {
                        board.board[x][y].stone = stone;
                    }
                }
            }
        }
    }
}
