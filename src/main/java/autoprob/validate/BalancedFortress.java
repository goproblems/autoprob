package autoprob.validate;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.StoneGroupLogic;

// fills the board around an isolated problem with static stones so katago can
// focus on the local fight. unlike StoneGroupLogic.buildFortress, the fill is
// split between the two colors so the resulting score stays close enough to
// zero for komi to make the life and death result decide the game (katago
// only accepts komi in [-150, 150]).
//
// layout, moving outward from the problem stones:
//   gap empty lines, a wall of the outward facing color, a wall of the other
//   color, one empty line, then checkerboard fill split into two single color
//   regions separated by an empty seam line. the seam is placed so that the
//   fill areas offset the empty problem zone, which mostly belongs to the
//   outward facing color.
public class BalancedFortress {

    // fills board, records every added stone in filled
    public void build(Board board, int gap, Board filled) {
        // bounding box of the problem stones
        int minX = 19, minY = 19, maxX = -1, maxY = -1;
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (board.board[x][y].stone == Intersection.EMPTY) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        if (maxX < 0) return; // empty board

        // which color faces outward
        StoneGroupLogic sgl = new StoneGroupLogic();
        int[] extents = sgl.calcGroupExtents(board);
        int wallColor = extents[4];
        int backColor = wallColor == Intersection.BLACK ? Intersection.WHITE : Intersection.BLACK;

        // problem zone including breathing room
        int zx0 = minX - gap, zx1 = maxX + gap;
        int zy0 = minY - gap, zy1 = maxY + gap;

        // two walls around the zone, then one empty line before the fill
        drawRing(board, filled, zx0 - 1, zy0 - 1, zx1 + 1, zy1 + 1, wallColor);
        drawRing(board, filled, zx0 - 2, zy0 - 2, zx1 + 2, zy1 + 2, backColor);
        // outer wall rectangle: empty points inside it lean toward the wall color
        int ox0 = zx0 - 2, oy0 = zy0 - 2, ox1 = zx1 + 2, oy1 = zy1 + 2;
        // fill starts one empty line beyond the outer wall
        int fx0 = zx0 - 3, fx1 = zx1 + 3;
        int fy0 = zy0 - 3, fy1 = zy1 + 3;

        // empty area inside the walls: assume it goes to the outward facing color
        int zoneBias = 0;
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (!inRect(x, y, ox0, oy0, ox1, oy1)) continue;
                if (board.board[x][y].stone == Intersection.EMPTY) zoneBias++;
            }

        // split the fill along the axis with the most room, into black (below the
        // seam) and white (above), choosing the seam so total areas balance
        boolean splitOnX = roomAlong(fx0, fx1) >= roomAlong(fy0, fy1);
        int bestSeam = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (int seam = 0; seam <= 19; seam++) {
            int blackArea = sideArea(ox0, oy0, ox1, oy1, splitOnX, seam, true);
            int whiteArea = sideArea(ox0, oy0, ox1, oy1, splitOnX, seam, false);
            if (wallColor == Intersection.BLACK) blackArea += zoneBias;
            else whiteArea += zoneBias;
            int diff = Math.abs(blackArea - whiteArea);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestSeam = seam;
            }
        }
        System.out.println("fortress: wall " + Intersection.color2name(wallColor)
                + ", zone bias " + zoneBias + ", seam at " + (splitOnX ? "x" : "y") + "=" + bestSeam
                + ", estimated imbalance " + bestDiff);
        if (bestDiff > 100) {
            System.out.println("WARNING: fortress fill imbalance is large, komi may not be able to compensate");
        }

        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (inRect(x, y, fx0, fy0, fx1, fy1)) continue; // zone + walls + gap line
                if ((x + y) % 2 != 0) continue; // checkerboard
                int t = splitOnX ? x : y;
                if (t == bestSeam) continue; // empty seam line
                int color = (t < bestSeam) ? Intersection.BLACK : Intersection.WHITE;
                board.board[x][y].stone = color;
                filled.board[x][y].stone = color;
            }
    }

    private boolean inRect(int x, int y, int x0, int y0, int x1, int y1) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }

    // how much space exists outside the zone along one axis
    private int roomAlong(int lo, int hi) {
        return Math.max(0, lo) + Math.max(0, 18 - hi);
    }

    // board area on one side of the seam, outside the outer wall rectangle
    private int sideArea(int ox0, int oy0, int ox1, int oy1, boolean splitOnX, int seam, boolean below) {
        int count = 0;
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (inRect(x, y, ox0, oy0, ox1, oy1)) continue;
                int t = splitOnX ? x : y;
                if (t == seam) continue;
                if ((t < seam) == below) count++;
            }
        return count;
    }

    // rectangle outline, clipped to the board, skipping occupied points
    private void drawRing(Board board, Board filled, int x0, int y0, int x1, int y1, int color) {
        for (int x = x0; x <= x1; x++) {
            ringStone(board, filled, x, y0, color);
            ringStone(board, filled, x, y1, color);
        }
        for (int y = y0; y <= y1; y++) {
            ringStone(board, filled, x0, y, color);
            ringStone(board, filled, x1, y, color);
        }
    }

    private void ringStone(Board board, Board filled, int x, int y, int color) {
        if (x < 0 || y < 0 || x >= 19 || y >= 19) return;
        if (board.board[x][y].stone != Intersection.EMPTY) return;
        board.board[x][y].stone = color;
        filled.board[x][y].stone = color;
    }
}
