package autoprob.validate;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import autoprob.go.Board;
import autoprob.go.Intersection;

// the tsumego frame of KaTrain (katrain/core/tsumego_frame.py, MIT license, by Sander Land),
// which kaorahi ported there from lizgoban. used instead of BalancedFortress when ko threats
// are wanted, because its ko threat only works inside its own fill.
//
// the problem is first flipped so it sits toward the bottom right. a border of the
// attacker's color goes around the problem at the margin, and the rest of the board is
// split in reading order between the defender (first) and the attacker, sized so the
// attacker wins by a few points when the problem is solved; both areas get checkerboard
// eyes, except near where they meet. with ko threats on, lizgoban's edge pattern gives the
// side to play a run of forcing ataris along an edge, so a ko counts as won by the side to
// play: the attacker gets its threat in the top right corner, the defender in the bottom left.
public class TsumegoFrame {
    private static final int NEAR_TO_EDGE = 2;
    private static final int OFFENCE_TO_WIN = 5;
    private static final int SIZE = 19;

    // X is the attacker, O the defender, . an empty point
    private static final String[] OFFENSE_KO_THREAT = {"....OOOX.", ".....XXXX"};
    private static final String[] DEFENSE_KO_THREAT = {"..", "..", "X.", "XO", "OO", ".O"};

    private static class Cell {
        boolean stone;
        boolean black;
        boolean frame;   // placed by the frame
        boolean threat;  // part of the ko threat pattern, stone or empty
    }

    // fills board around the problem, recording every added stone in filled. returns the
    // points of the ko threat pattern (empty when ko threats are off or there was no room),
    // where the side to play must be allowed to move
    public List<Point> build(Board board, int margin, int colorToPlay, double komi, boolean koThreats, Board filled) {
        Cell[][] stones = new Cell[SIZE][SIZE];
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++) {
                stones[i][j] = new Cell();
                int s = board.board[j][i].stone;
                if (s != Intersection.EMPTY) {
                    stones[i][j].stone = true;
                    stones[i][j].black = s == Intersection.BLACK;
                }
            }
        stones = frame(stones, komi, colorToPlay == Intersection.BLACK, koThreats, margin);
        List<Point> threats = new ArrayList<>();
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++) {
                Cell c = stones[i][j];
                if (c.threat) threats.add(new Point(j, i));
                if (!c.frame) continue;
                int color = c.black ? Intersection.BLACK : Intersection.WHITE;
                board.board[j][i].stone = c.stone ? color : Intersection.EMPTY;
                filled.board[j][i].stone = c.stone ? color : Intersection.EMPTY;
            }
        return threats;
    }

    private Cell[][] frame(Cell[][] stones, double komi, boolean blackToPlay, boolean ko, int margin) {
        int top = -1, bottom = -1, left = -1, right = -1;
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++) {
                if (!stones[i][j].stone) continue;
                if (top < 0 || i < top) top = i;
                if (bottom < 0 || i > bottom) bottom = i;
                if (left < 0 || j < left) left = j;
                if (right < 0 || j > right) right = j;
            }
        if (top < 0) return stones;
        int imin = snap(top, 0), jmin = snap(left, 0);
        int imax = snap(bottom, SIZE - 1), jmax = snap(right, SIZE - 1);
        // flip or swap to the standard position, never both at once
        boolean[] flip = imin < jmin ? new boolean[]{false, false, true}
                : new boolean[]{needFlip(imin, imax), needFlip(jmin, jmax), false};
        if (flip[0] || flip[1] || flip[2]) {
            Cell[][] filled = frame(flipStones(stones, flip), komi, blackToPlay, ko, margin);
            return flipStones(filled, flip);
        }
        int i0 = imin - margin, i1 = imax + margin, j0 = jmin - margin, j1 = jmax + margin;
        boolean blackToAttack = guessBlackToAttack(stones, top, bottom, left, right);
        putBorder(stones, i0, i1, j0, j1, blackToAttack);
        putOutside(stones, i0, i1, j0, j1, blackToAttack, komi);
        if (ko) putKoThreat(stones, i0, i1, j0, j1, blackToAttack, blackToPlay);
        return stones;
    }

    // a problem within two lines of an edge is treated as touching it
    private static int snap(int k, int to) {
        return Math.abs(k - to) <= NEAR_TO_EDGE ? to : k;
    }

    private static boolean needFlip(int kmin, int kmax) {
        return kmin < SIZE - kmax - 1;
    }

    // the color whose outermost stones sit further from the edges is taken to be attacking
    private static boolean guessBlackToAttack(Cell[][] stones, int top, int bottom, int left, int right) {
        double sum = 0;
        for (Point p : extremes(stones, top, bottom, left, right)) {
            Cell c = stones[p.y][p.x];
            sum += (c.black ? 1 : -1) * (height(p.y) + height(p.x));
        }
        return sum > 0;
    }

    // the first stone found at each extreme, in the order katrain finds them
    private static List<Point> extremes(Cell[][] stones, int top, int bottom, int left, int right) {
        List<Point> out = new ArrayList<>();
        out.add(first(stones, (i, j) -> i == top));
        out.add(first(stones, (i, j) -> i == bottom));
        out.add(first(stones, (i, j) -> j == left));
        out.add(first(stones, (i, j) -> j == right));
        return out;
    }

    private interface At {
        boolean test(int i, int j);
    }

    private static Point first(Cell[][] stones, At at) {
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++) {
                if (stones[i][j].stone && at.test(i, j)) return new Point(j, i);
            }
        return null;
    }

    private static double height(int k) {
        return SIZE - Math.abs(k - (SIZE - 1) / 2.0);
    }

    private static void putBorder(Cell[][] stones, int i0, int i1, int j0, int j1, boolean black) {
        for (int at : new int[]{j0, j1})
            for (int k = i0; k <= i1; k++) put(stones, k, at, black, false, false);
        for (int at : new int[]{i0, i1})
            for (int k = j0; k <= j1; k++) put(stones, at, k, black, false, false);
    }

    private static void putOutside(Cell[][] stones, int i0, int i1, int j0, int j1, boolean blackToAttack, double komi) {
        int count = 0;
        double offenseKomi = (blackToAttack ? 1 : -1) * komi;
        double defenseArea = (SIZE * SIZE - offenseKomi - OFFENCE_TO_WIN) / 2;
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++) {
                if (inside(i, j, i0, i1, j0, j1)) continue;
                count++;
                boolean black = blackToAttack != (count <= defenseArea);
                boolean empty = (i + j) % 2 == 0 && Math.abs(count - defenseArea) > SIZE;
                put(stones, i, j, black, empty, false);
            }
    }

    private static void putKoThreat(Cell[][] stones, int i0, int i1, int j0, int j1, boolean blackToAttack,
                                    boolean blackToPlay) {
        // the side to play gets the threats: the attacker's pattern if it is attacking
        boolean forOffense = blackToAttack == blackToPlay;
        String[] pattern = forOffense ? OFFENSE_KO_THREAT : DEFENSE_KO_THREAT;
        boolean top = forOffense;
        boolean leftSide = !forOffense;
        int height = pattern.length, width = pattern[0].length();
        for (int i = 0; i < height; i++)
            for (int j = 0; j < width; j++) {
                int ai = i + (top ? 0 : SIZE - height);
                int aj = j + (leftSide ? 0 : SIZE - width);
                if (inside(ai, aj, i0, i1, j0, j1)) return;
                char ch = pattern[i].charAt(j);
                put(stones, ai, aj, blackToAttack != (ch == 'O'), ch == '.', true);
            }
    }

    private static boolean inside(int i, int j, int i0, int i1, int j0, int j1) {
        return i0 <= i && i <= i1 && j0 <= j && j <= j1;
    }

    private static void put(Cell[][] stones, int i, int j, boolean black, boolean empty, boolean threat) {
        if (i < 0 || i >= SIZE || j < 0 || j >= SIZE) return;
        Cell c = new Cell();
        c.stone = !empty;
        c.black = black;
        c.frame = true;
        c.threat = threat;
        stones[i][j] = c;
    }

    private static Cell[][] flipStones(Cell[][] stones, boolean[] flip) {
        Cell[][] out = new Cell[SIZE][SIZE];
        for (int i = 0; i < SIZE; i++)
            for (int j = 0; j < SIZE; j++) {
                int fi = flip[0] ? SIZE - 1 - i : i;
                int fj = flip[1] ? SIZE - 1 - j : j;
                if (flip[2]) out[fj][fi] = stones[i][j];
                else out[fi][fj] = stones[i][j];
            }
        return out;
    }
}
