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
    // the eyes each color's fill keeps when there is room: a walk takes at most one of each
    // side's per step (RegimeWalker.MAX_STEPS, 12), and seldom more than a few
    static final int EYES = 8;

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
        // seam) and white (above), choosing the seam so total areas balance. each color keeps
        // a few eyes in its fill, where the regime walker plays its free exchanges, its stand-in
        // for ko threats (RegimeWalker.exchange): a color with none could never be answered
        // when it takes a ko (gp-62416: the zone counted to black, the whole fill went white,
        // and white could never take back a ko black took, so a ko came out a clean kill). when
        // keeping them costs balance along that axis, the other may do it with less; when the
        // fill is too small, the seam leaves the side with fewer as many as it can
        boolean splitOnX = roomAlong(fx0, fx1) >= roomAlong(fy0, fy1);
        int[] best = seam(splitOnX, ox0, oy0, ox1, oy1, fx0, fy0, fx1, fy1, wallColor, zoneBias, true);
        if (best[1] < EYES || best[2] > seam(splitOnX, ox0, oy0, ox1, oy1, fx0, fy0, fx1, fy1, wallColor, zoneBias, false)[2]) {
            int[] other = seam(!splitOnX, ox0, oy0, ox1, oy1, fx0, fy0, fx1, fy1, wallColor, zoneBias, true);
            if (other[1] > best[1] || (other[1] == best[1] && other[2] < best[2])) {
                best = other;
                splitOnX = !splitOnX;
            }
        }
        int bestSeam = best[0], bestDiff = best[2];
        System.out.println("fortress: wall " + Intersection.color2name(wallColor)
                + ", zone bias " + zoneBias + ", seam at " + (splitOnX ? "x" : "y") + "=" + bestSeam
                + ", estimated imbalance " + bestDiff);
        if (bestDiff > 100) {
            System.out.println("WARNING: fortress fill imbalance is large, komi may not be able to compensate");
        }

        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                int color = fill(x, y, fx0, fy0, fx1, fy1, splitOnX, bestSeam);
                if (color == Intersection.EMPTY) continue;
                board.board[x][y].stone = color;
                filled.board[x][y].stone = color;
            }
    }

    // the seam along an axis that balances the areas best, among those leaving each color the
    // most eyes up to EYES when keepEyes: the seam, those eyes, and how far off the balance is
    private int[] seam(boolean splitOnX, int ox0, int oy0, int ox1, int oy1, int fx0, int fy0, int fx1, int fy1,
                       int wallColor, int zoneBias, boolean keepEyes) {
        int[] best = {-1, -1, Integer.MAX_VALUE};
        for (int seam = 0; seam <= 19; seam++) {
            int blackArea = sideArea(ox0, oy0, ox1, oy1, splitOnX, seam, true);
            int whiteArea = sideArea(ox0, oy0, ox1, oy1, splitOnX, seam, false);
            if (wallColor == Intersection.BLACK) blackArea += zoneBias;
            else whiteArea += zoneBias;
            int diff = Math.abs(blackArea - whiteArea);
            int eyes = !keepEyes ? 0 : Math.min(EYES, Math.min(eyes(fx0, fy0, fx1, fy1, splitOnX, seam, Intersection.BLACK),
                    eyes(fx0, fy0, fx1, fy1, splitOnX, seam, Intersection.WHITE)));
            if (eyes > best[1] || (eyes == best[1] && diff < best[2])) best = new int[]{seam, eyes, diff};
        }
        return best;
    }

    // the stone the fill puts at a point, or EMPTY: a checkerboard outside the zone, its walls
    // and the gap line, black below the seam and white above, the seam line left empty
    private static int fill(int x, int y, int fx0, int fy0, int fx1, int fy1, boolean splitOnX, int seam) {
        if (inRect(x, y, fx0, fy0, fx1, fy1) || (x + y) % 2 != 0) return Intersection.EMPTY;
        int t = splitOnX ? x : y;
        if (t == seam) return Intersection.EMPTY;
        return t < seam ? Intersection.BLACK : Intersection.WHITE;
    }

    // how many points the fill leaves empty with the color's fill stones all round (the
    // board's edge aside): its eyes, as RegimeWalker.neutralPoints finds them
    static int eyes(int fx0, int fy0, int fx1, int fy1, boolean splitOnX, int seam, int color) {
        int n = 0;
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (inRect(x, y, fx0, fy0, fx1, fy1) || fill(x, y, fx0, fy0, fx1, fy1, splitOnX, seam) != Intersection.EMPTY) continue;
                boolean eye = true;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx < 0 || ny < 0 || nx >= 19 || ny >= 19) continue;
                    if (fill(nx, ny, fx0, fy0, fx1, fy1, splitOnX, seam) != color) eye = false;
                }
                if (eye) n++;
            }
        return n;
    }

    private static boolean inRect(int x, int y, int x0, int y0, int x1, int y1) {
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
