package autoprob.collection;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;

/** Detects the GoProblems "Center tubes" collection's 3-by-X shape. */
public final class CenterTubesDetector implements CollectionDetector {
    @Override
    public String name() {
        return "center-tubes";
    }

    @Override
    public MatchResult detect(Node startingPosition) {
        Board board = startingPosition.board;

        Bounds black = findBounds(board, Intersection.BLACK);
        Bounds white = findBounds(board, Intersection.WHITE);

        if (isCenterTube(board, black, white)) {
            return MatchResult.match(describe("black", black));
        }
        if (isCenterTube(board, white, black)) {
            return MatchResult.match(describe("white", white));
        }

        return MatchResult.noMatch("neither color forms an interior 3-by-X tube surrounded on four sides; "
                + describe("black", black) + ", " + describe("white", white));
    }

    private Bounds findBounds(Board board, int color) {
        Bounds bounds = new Bounds();
        for (int y = 0; y < board.boardY; y++) {
            for (int x = 0; x < board.boardX; x++) {
                if (board.board[x][y].stone == color) {
                    bounds.include(x, y);
                }
            }
        }
        return bounds;
    }

    private boolean isCenterTube(Board board, Bounds bounds, Bounds opponent) {
        if (bounds.stoneCount == 0 || opponent.stoneCount == 0) {
            return false;
        }

        boolean verticalTube = bounds.width() == 3 && bounds.height() >= 5
                && bounds.minX >= 3 && bounds.maxX <= board.boardX - 4;
        boolean horizontalTube = bounds.height() == 3 && bounds.width() >= 5
                && bounds.minY >= 3 && bounds.maxY <= board.boardY - 4;
        boolean threeByXWithLongSideClearance = verticalTube || horizontalTube;
        boolean awayFromEdge = bounds.minX > 0 && bounds.minY > 0
                && bounds.maxX < board.boardX - 1 && bounds.maxY < board.boardY - 1;
        boolean opponentOnAllFourSides = opponent.minX < bounds.minX
                && opponent.maxX > bounds.maxX
                && opponent.minY < bounds.minY
                && opponent.maxY > bounds.maxY;
        return threeByXWithLongSideClearance && awayFromEdge && opponentOnAllFourSides;
    }

    private String describe(String color, Bounds bounds) {
        if (bounds.stoneCount == 0) {
            return color + " has no stones";
        }
        return color + " stones=" + bounds.stoneCount
                + " bounds=" + bounds.width() + "x" + bounds.height();
    }

    private static final class Bounds {
        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int stoneCount;

        private void include(int x, int y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            stoneCount++;
        }

        private int width() {
            return stoneCount == 0 ? 0 : maxX - minX + 1;
        }

        private int height() {
            return stoneCount == 0 ? 0 : maxY - minY + 1;
        }
    }
}
