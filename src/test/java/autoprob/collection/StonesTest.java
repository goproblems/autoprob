package autoprob.collection;

import autoprob.go.Intersection;
import autoprob.go.parse.Parser;
import org.junit.jupiter.api.Test;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StonesTest {
    // GoProblems 43924, the lower left corner:
    //   6 . . O . . .
    //   5 . . O . . .
    //   4 . O X X X .
    //   3 . O X O . X
    //   2 X X O O . X
    //   1 . X O . . .
    //     A B C D E F
    private static final String RACE =
            "(;SZ[19]AB[ar][br][bs][cp][cq][dp][ep][fq][fr]AW[bq][bp][cr][cs][dr][dq][co][cn])";

    @Test
    void findsAChainWithItsLiberties() throws Exception {
        Stones stones = stones(RACE);
        Stones.Chain corner = stones.chain(at(stones, "B2"));

        assertEquals(Stones.BLACK, corner.color);
        assertArrayEquals(new int[] {at(stones, "A2"), at(stones, "B2"), at(stones, "B1")}, corner.stones);
        assertArrayEquals(new int[] {at(stones, "A3"), at(stones, "A1")}, corner.liberties);
        assertTrue(corner.contains(at(stones, "A2")));
        assertFalse(corner.contains(at(stones, "C2")));
        assertEquals(3, stones.chains(Stones.BLACK).size());
        assertEquals(3, stones.chains(Stones.WHITE).size());
    }

    @Test
    void capturesAndRefusesSuicide() throws Exception {
        Stones stones = stones(RACE);

        assertNull(stones.play(at(stones, "A2"), Stones.WHITE), "the point is taken");
        assertNull(stones.play(at(stones, "A1"), Stones.WHITE), "no liberty and nothing captured");
        assertNotNull(stones.play(at(stones, "A1"), Stones.BLACK));

        Stones filled = stones.play(at(stones, "A3"), Stones.WHITE);
        assertEquals(0, filled.captured().length);
        Stones captured = filled.play(at(stones, "A1"), Stones.WHITE);
        assertEquals(3, captured.captured().length);
        assertEquals(Stones.EMPTY, captured.at(at(stones, "B2")));
        assertEquals(Stones.BLACK, stones.at(at(stones, "B2")), "a position is not changed by playing on it");
    }

    @Test
    void countsTheMovesBeforeAChainIsCaptured() throws Exception {
        Stones stones = stones(RACE);
        int corner = at(stones, "A2");

        // A3, then A1
        assertEquals(2, stones.movesToCapture(corner, 6, -1));

        // with black on B5, white at A3 would have one liberty: B5 has to be taken first, in two moves
        Stones sacrificed = stones.play(at(stones, "B5"), Stones.BLACK);
        assertEquals(4, sacrificed.movesToCapture(corner, 6, -1));
        // without taking it white has to run along the edge for liberties
        assertEquals(5, sacrificed.movesToCapture(corner, 6, at(stones, "B5")));
        // and B5 itself falls in two
        assertEquals(2, sacrificed.movesToCapture(at(stones, "B5"), 6, -1));

        assertEquals(3, sacrificed.movesToCapture(corner, 2, -1), "one more than the limit when it takes longer");
    }

    @Test
    void countsNoMoveThatShortensAChainBeingRaced() throws Exception {
        // GoProblems 47577 with black's cut at M8 played, the upper right corner of a 13 line board:
        //  13 . . . . . . . .
        //  12 . . X X O O O X
        //  11 X . X O X X X X
        //  10 . . . O O X O .
        //   9 . . . . O X O .
        //   8 . . . . O O X .
        //     F G H J K L M N
        Stones stones = stones("(;SZ[13]AB[kc][jc][kd][ke][ib][hb][hc][fc][mb][mc][lc][lf]"
                + "AW[le][ld][kf][jf][je][id][ic][jb][lb][kb][jd])");
        int corner = at(stones, "N12");
        int[] top = {at(stones, "L12")};

        // N10 would leave white's two stones on one liberty, so M8 is taken first: M7, N8, N10, N13.
        // Or, black never answering, round the top: M13, N13, and N10 is the capture
        assertEquals(3, stones.movesToCapture(corner, 6, -1));
        // but that lengthens the three stones at the top onto N13 and leaves them a liberty
        // shorter: with black racing those three it is no move gained
        assertEquals(4, stones.movesToCapture(corner, 6, -1, top));
        // and without taking M8 white has to run down the edge for liberties: N9, N8, N7, N10, N13
        assertEquals(5, stones.movesToCapture(corner, 6, at(stones, "M8"), top));
        // a move that is the capture may shorten them: N13 here, once N10 is filled
        Stones open = stones("(;SZ[13]AB[kc][jc][kd][ke][ib][hb][hc][fc][mb][mc][lc]"
                + "AW[le][ld][kf][jf][je][id][ic][jb][lb][kb][jd])");
        assertEquals(2, open.movesToCapture(corner, 6, -1, top));
    }

    @Test
    void readsWhetherAStoneCanGetAway() throws Exception {
        Stones stones = stones(RACE);
        Stones sacrificed = stones.play(at(stones, "B5"), Stones.BLACK);
        assertTrue(sacrificed.capturable(at(stones, "B5")), "a ladder into the edge");
        assertFalse(sacrificed.capturable(at(stones, "F3")), "four liberties");

        // black K10 with white on J10 and K11: two liberties, and three after either atari
        Stones open = stones("(;SZ[19]AB[jj]AW[ij][ji])");
        assertFalse(open.capturable(at(open, "K10")));

        // the same with white on L9 as well: the ladder works
        Stones netted = stones("(;SZ[19]AB[jj]AW[ij][ji][kk])");
        assertTrue(netted.capturable(at(netted, "K10")));
    }

    private static Stones stones(String sgf) throws Exception {
        return Stones.of(new Parser().parse(sgf).board);
    }

    /** The point of that name; the name is read as on a 19 line board, so smaller ones are shifted. */
    private static int at(Stones stones, String name) {
        Point point = Intersection.gtp2point(name);
        return stones.point(point.x, point.y - (19 - stones.height));
    }
}
