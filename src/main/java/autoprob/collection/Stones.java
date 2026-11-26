package autoprob.collection;

import autoprob.go.Board;
import autoprob.go.Intersection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A go position for the small amount of reading a collection detector needs: chains and their
 * liberties, legal play with captures, and how many moves a chain is from being captured.
 * A point is y * width + x; a position is never changed once made.
 */
final class Stones {
    static final int EMPTY = Intersection.EMPTY;
    static final int BLACK = Intersection.BLACK;
    static final int WHITE = Intersection.WHITE;

    /** What {@link #movesToCapture} answers when the search grew too big to finish. */
    static final int UNKNOWN = -1;

    private static final int MAX_POSITIONS = 20_000;
    private static final int APPROACH_DEPTH = 3;
    private static final int LADDER_DEPTH = 40;
    private static final int[] NONE = new int[0];

    final int width;
    final int height;
    private final int[] cells;
    /** The stones the move that made this position took off the board. */
    private int[] captured = NONE;

    private Stones(int width, int height, int[] cells) {
        this.width = width;
        this.height = height;
        this.cells = cells;
    }

    static Stones of(Board board) {
        int[] cells = new int[board.boardX * board.boardY];
        for (int y = 0; y < board.boardY; y++) {
            for (int x = 0; x < board.boardX; x++) {
                int stone = board.board[x][y].stone;
                cells[y * board.boardX + x] = stone == BLACK || stone == WHITE ? stone : EMPTY;
            }
        }
        return new Stones(board.boardX, board.boardY, cells);
    }

    static int other(int color) {
        return color == BLACK ? WHITE : BLACK;
    }

    int point(int x, int y) {
        return y * width + x;
    }

    int x(int point) {
        return point % width;
    }

    int y(int point) {
        return point / width;
    }

    boolean onBoard(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    int at(int point) {
        return cells[point];
    }

    int points() {
        return cells.length;
    }

    int[] captured() {
        return captured;
    }

    /** The point's name as the site shows it: A1 is the bottom left corner and there is no I. */
    String name(int point) {
        return Intersection.toGTPloc(x(point), y(point), height);
    }

    int[] neighbors(int point) {
        int x = x(point);
        int y = y(point);
        int[] found = new int[4];
        int count = 0;
        if (x > 0) found[count++] = point - 1;
        if (x < width - 1) found[count++] = point + 1;
        if (y > 0) found[count++] = point - width;
        if (y < height - 1) found[count++] = point + width;
        return count == 4 ? found : Arrays.copyOf(found, count);
    }

    /** The chain of stones the stone at the point belongs to. */
    Chain chain(int point) {
        int color = cells[point];
        if (color == EMPTY) {
            throw new IllegalArgumentException("no stone at " + name(point));
        }
        boolean[] seen = new boolean[cells.length];
        int[] stones = new int[cells.length];
        int[] liberties = new int[cells.length];
        int stoneCount = 0;
        int libertyCount = 0;
        stones[stoneCount++] = point;
        seen[point] = true;
        for (int next = 0; next < stoneCount; next++) {
            for (int neighbor : neighbors(stones[next])) {
                if (seen[neighbor]) continue;
                if (cells[neighbor] == color) {
                    seen[neighbor] = true;
                    stones[stoneCount++] = neighbor;
                } else if (cells[neighbor] == EMPTY) {
                    seen[neighbor] = true;
                    liberties[libertyCount++] = neighbor;
                }
            }
        }
        int[] chainStones = Arrays.copyOf(stones, stoneCount);
        Arrays.sort(chainStones);
        int[] chainLiberties = Arrays.copyOf(liberties, libertyCount);
        Arrays.sort(chainLiberties);
        return new Chain(color, chainStones, chainLiberties);
    }

    /** Every chain of the color, each once. */
    List<Chain> chains(int color) {
        List<Chain> found = new ArrayList<>();
        boolean[] seen = new boolean[cells.length];
        for (int point = 0; point < cells.length; point++) {
            if (cells[point] != color || seen[point]) continue;
            Chain chain = chain(point);
            for (int stone : chain.stones) seen[stone] = true;
            found.add(chain);
        }
        return found;
    }

    /** The position after the move, or null when the point is taken or the move would be suicide. */
    Stones play(int point, int color) {
        if (cells[point] != EMPTY) return null;
        Stones next = new Stones(width, height, cells.clone());
        next.cells[point] = color;
        int enemy = other(color);
        int[] taken = NONE;
        for (int neighbor : neighbors(point)) {
            if (next.cells[neighbor] != enemy) continue;
            Chain chain = next.chain(neighbor);
            if (chain.liberties.length > 0) continue;
            for (int stone : chain.stones) next.cells[stone] = EMPTY;
            int from = taken.length;
            taken = Arrays.copyOf(taken, from + chain.stones.length);
            System.arraycopy(chain.stones, 0, taken, from, chain.stones.length);
        }
        if (taken.length == 0 && next.chain(point).liberties.length == 0) return null;
        next.captured = taken;
        return next;
    }

    /**
     * How many moves in a row the other side needs to capture the chain at the target, the chain's
     * owner never answering: its liberties, counting the moves that have to come first where one
     * cannot be filled as it stands. A move that leaves its own chain with a single liberty is not
     * counted as possible, since the owner would capture it; so a liberty is worth more than one
     * move when the stones that fill it would be short of liberties themselves.
     *
     * @param keep a stone of the chain's owner that may not be captured on the way, or -1
     * @return the number of moves, limit + 1 when it takes more, or UNKNOWN when the search got
     * too big
     */
    int movesToCapture(int target, int limit, int keep) {
        return movesToCapture(target, limit, keep, NONE);
    }

    /**
     * The same count where the chain's owner is out to capture chains of the other side in turn.
     * A move that leaves one of those with fewer liberties than it has now is not counted as
     * possible either, unless it is the capture itself: what it fills for the one side it gives
     * back to the other, so in the race it is no move gained. That is the approach made by
     * lengthening the raced chain onto a liberty both then share.
     *
     * @param raced a stone of each chain of the capturing side that the owner is racing
     */
    int movesToCapture(int target, int limit, int keep, int[] raced) {
        int defender = cells[target];
        int attacker = other(defender);
        int[] least = new int[raced.length];
        for (int i = 0; i < raced.length; i++) {
            if (cells[raced[i]] != attacker) {
                throw new IllegalArgumentException("no " + Intersection.color2name(attacker) + " stone at " + name(raced[i]));
            }
            least[i] = chain(raced[i]).liberties.length;
        }
        List<Stones> frontier = List.of(this);
        Set<String> seen = new HashSet<>();
        seen.add(key());
        for (int depth = 1; depth <= limit; depth++) {
            List<Stones> reached = new ArrayList<>();
            for (Stones position : frontier) {
                for (int move : position.capturingTries(target)) {
                    Stones next = position.play(move, attacker);
                    if (next == null) continue;
                    if (keep >= 0 && next.cells[keep] != defender) continue;
                    if (next.cells[target] != defender) return depth;
                    if (next.chain(move).liberties.length < 2) continue;
                    if (next.shorter(raced, least)) continue;
                    if (!seen.add(next.key())) continue;
                    if (seen.size() > MAX_POSITIONS) return UNKNOWN;
                    reached.add(next);
                }
            }
            if (reached.isEmpty()) break;
            frontier = reached;
        }
        return limit + 1;
    }

    /** Whether the chain at one of the stones has fewer liberties than the number given for it. */
    private boolean shorter(int[] stones, int[] liberties) {
        for (int i = 0; i < stones.length; i++) {
            if (chain(stones[i]).liberties.length < liberties[i]) return true;
        }
        return false;
    }

    /**
     * The points worth trying for the side out to capture the chain at the target: the chain's
     * liberties, and for each of them that cannot be filled as it stands the moves that would
     * make it possible, which are the liberties the filling stones would have and those of the
     * owner's short chains standing against them. The same again for those moves, three deep.
     */
    private Set<Integer> capturingTries(int target) {
        int defender = cells[target];
        int attacker = other(defender);
        Chain chain = chain(target);
        Set<Integer> tries = new LinkedHashSet<>();
        for (int liberty : chain.liberties) tries.add(liberty);

        List<Integer> open = new ArrayList<>(tries);
        for (int round = 0; round < APPROACH_DEPTH && !open.isEmpty(); round++) {
            List<Integer> added = new ArrayList<>();
            for (int point : open) {
                Stones next = play(point, attacker);
                if (next != null && (next.cells[target] != defender || next.chain(point).liberties.length >= 2)) {
                    continue;
                }
                if (next != null) {
                    for (int free : next.chain(point).liberties) {
                        if (tries.add(free)) added.add(free);
                    }
                }
                // the stones the move would join, and the owner's short chains touching any of them
                Set<Integer> joined = new LinkedHashSet<>();
                joined.add(point);
                for (int beside : neighbors(point)) {
                    if (cells[beside] != attacker || joined.contains(beside)) continue;
                    for (int stone : chain(beside).stones) joined.add(stone);
                }
                Set<Integer> handled = new HashSet<>();
                for (int stone : joined) {
                    for (int beside : neighbors(stone)) {
                        if (cells[beside] != defender || chain.contains(beside) || !handled.add(beside)) continue;
                        Chain blocker = chain(beside);
                        for (int other : blocker.stones) handled.add(other);
                        if (blocker.liberties.length > 3) continue;
                        for (int free : blocker.liberties) {
                            if (tries.add(free)) added.add(free);
                        }
                    }
                }
            }
            open = added;
        }
        return tries;
    }

    /**
     * True when the other side, playing first, captures the chain at the point whatever its owner
     * answers: the chain has one liberty, or two and no way out of the ladder.
     */
    boolean capturable(int point) {
        return capturable(point, LADDER_DEPTH);
    }

    private boolean capturable(int point, int depth) {
        int attacker = other(cells[point]);
        Chain chain = chain(point);
        if (chain.liberties.length == 1) return play(chain.liberties[0], attacker) != null;
        if (chain.liberties.length != 2 || depth <= 0) return false;
        for (int liberty : chain.liberties) {
            Stones next = play(liberty, attacker);
            if (next != null && !next.escapes(point, depth - 1)) return true;
        }
        return false;
    }

    /** The chain at the point is in atari and its owner is to play: does it get away? */
    private boolean escapes(int point, int depth) {
        int defender = cells[point];
        Chain chain = chain(point);
        Set<Integer> tries = new LinkedHashSet<>();
        for (int liberty : chain.liberties) tries.add(liberty);
        for (int stone : chain.stones) {
            for (int beside : neighbors(stone)) {
                if (cells[beside] != other(defender)) continue;
                Chain attacker = chain(beside);
                if (attacker.liberties.length == 1) tries.add(attacker.liberties[0]);
            }
        }
        for (int move : tries) {
            Stones next = play(move, defender);
            if (next == null) continue;
            int liberties = next.chain(point).liberties.length;
            if (liberties >= 3) return true;
            if (liberties == 2 && !next.capturable(point, depth - 1)) return true;
        }
        return false;
    }

    String key() {
        char[] chars = new char[cells.length];
        for (int i = 0; i < cells.length; i++) chars[i] = (char) ('0' + cells[i]);
        return new String(chars);
    }

    @Override
    public String toString() {
        StringBuilder rows = new StringBuilder();
        for (int y = 0; y < height; y++) {
            if (y > 0) rows.append('\n');
            for (int x = 0; x < width; x++) {
                int stone = cells[point(x, y)];
                rows.append(stone == BLACK ? 'X' : stone == WHITE ? 'O' : '.');
            }
        }
        return rows.toString();
    }

    /** One chain: its stones and liberties, both in point order. */
    static final class Chain {
        final int color;
        final int[] stones;
        final int[] liberties;

        private Chain(int color, int[] stones, int[] liberties) {
            this.color = color;
            this.stones = stones;
            this.liberties = liberties;
        }

        boolean contains(int point) {
            return Arrays.binarySearch(stones, point) >= 0;
        }

        boolean hasLiberty(int point) {
            return Arrays.binarySearch(liberties, point) >= 0;
        }
    }
}
