package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.util.List;

import org.junit.jupiter.api.Test;

import autoprob.go.Board;
import autoprob.go.Intersection;

class TsumegoFrameTest {
    // gp-36582: black's corner inside white's wall, white to kill
    private static final String[] BLACK = {"O18", "P18", "Q17", "R19", "R17", "S16", "T16"};
    private static final String[] WHITE = {"N18", "N17", "O17", "P17", "Q16", "R16", "R15", "S15", "T15"};

    private static Board problem() {
        Board b = new Board();
        for (String s : BLACK) put(b, s, Intersection.BLACK);
        for (String s : WHITE) put(b, s, Intersection.WHITE);
        return b;
    }

    private static void put(Board b, String gtp, int color) {
        Point p = Intersection.gtp2point(gtp);
        b.board[p.x][p.y].stone = color;
    }

    @Test
    void framesTheProblemAndGivesTheSideToPlayKoThreats() {
        Board board = problem();
        Board filled = new Board();
        List<Point> threats = new TsumegoFrame().build(board, 4, Intersection.WHITE, 7.5, true, filled);

        // the problem is untouched and every added stone is recorded as filled
        for (String s : BLACK) assertEquals(Intersection.BLACK, board.board[gtp(s).x][gtp(s).y].stone);
        for (String s : WHITE) assertEquals(Intersection.WHITE, board.board[gtp(s).x][gtp(s).y].stone);
        int added = 0;
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (filled.board[x][y].stone == Intersection.EMPTY) continue;
                added++;
                assertEquals(filled.board[x][y].stone, board.board[x][y].stone);
            }
        assertTrue(added > 150, "the rest of the board is filled: " + added);

        // white attacks and is to play, so it gets the attacker's edge pattern, away from the problem
        assertEquals(18, threats.size());
        boolean whiteInPattern = false;
        for (Point t : threats) {
            assertTrue(t.y > 10, "the pattern sits far from the top right problem: " + t);
            whiteInPattern |= board.board[t.x][t.y].stone == Intersection.WHITE;
        }
        assertTrue(whiteInPattern);
    }

    @Test
    void noThreatsWithoutKo() {
        Board filled = new Board();
        List<Point> threats = new TsumegoFrame().build(problem(), 4, Intersection.WHITE, 7.5, false, filled);
        assertTrue(threats.isEmpty());
        assertFalse(filled.board[0][18].stone == Intersection.EMPTY && filled.board[1][18].stone == Intersection.EMPTY,
                "the far corner is filled");
    }

    private static Point gtp(String s) {
        return Intersection.gtp2point(s);
    }
}
