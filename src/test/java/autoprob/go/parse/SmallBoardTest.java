package autoprob.go.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SmallBoardTest {
    @Test
    void aFullBoardStaysAsItIs() {
        String sgf = "(;SZ[19]AB[aa][bb]AW[cc];B[dd]C[RIGHT])";
        SmallBoard.Embedded e = SmallBoard.toNineteen(sgf);
        assertSame(sgf, e.sgf());
        assertFalse(e.moved());
    }

    @Test
    void aTopRightCornerKeepsTheTopAndRightEdges() throws Exception {
        // gp-62114's corner: stones on the 13x13 board's right edge (column m)
        String sgf = "(;DI[15 kyu]SZ[13]AB[ma][la]AW[lb][kc]AN[2](;B[lc];W[ld]C[x \\] y RIGHT])(;B[mb]))";
        SmallBoard.Embedded e = SmallBoard.toNineteen(sgf);
        assertTrue(e.moved());
        assertEquals(13, e.size());
        assertEquals(6, e.dx());
        assertEquals(0, e.dy());
        assertEquals("(;DI[15 kyu]SZ[19]AB[sa][ra]AW[rb][qc]AN[2](;B[rc];W[rd]C[x \\] y RIGHT])(;B[sb]))", e.sgf());
        // the result parses as a 19x19 problem with the stones in its corner
        new Parser().parse(e.sgf());
    }

    @Test
    void aBottomLeftCornerKeepsTheBottomAndLeftEdges() {
        String sgf = "(;SZ[13]AB[am] [bl]AW[cl:cm]LB[bm:A]TR[ak];B[bm];W[tt])";
        SmallBoard.Embedded e = SmallBoard.toNineteen(sgf);
        assertEquals(0, e.dx());
        assertEquals(6, e.dy());
        assertEquals("(;SZ[19]AB[as] [br]AW[cr:cs]LB[bs:A]TR[aq];B[bs];W[tt])", e.sgf());
    }

    @Test
    void aProblemNearBothEdgesOfAnAxisIsRefused() {
        // stones from column a to column l of a 13x13 board: one line short of the right edge
        String sgf = "(;SZ[13]AB[aa][la]AW[ab];B[bb])";
        assertThrows(IllegalArgumentException.class, () -> SmallBoard.toNineteen(sgf));
    }
}
