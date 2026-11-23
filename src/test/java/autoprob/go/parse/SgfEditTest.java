package autoprob.go.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

public class SgfEditTest {
    // gp-62070 as the site has it
    public static final String GP62070 = "(;FF[4]GM[1]CA[UTF-8]AP[goproblems:0.1.0]SZ[13]AB[jb][jc][hc][he][ie][je][kf][lf][lg]"
            + "AW[ke][jd][kc][kb][lb][mc][md][mf]AN[2](;B[ld];W[le](;B[kd];W[lc];B[kd]C[RIGHT])(;B[ka]C[NOTTHIS]))"
            + "(;B[ka];W[la];B[ld];W[le];B[kd];W[lc];B[kd]C[RIGHT])(;B[le];W[ld])(;B[kd];W[ld])(;B[id];W[le];B[kd];W[ld]))";

    @Test
    void aProblemReadsBackAsItWasWritten() {
        assertEquals(GP62070, SgfEdit.parse(GP62070).write());
        // space between nodes goes, the nodes stay as written
        assertEquals("(;SZ[13]C[a ; b (c)](;B[aa])(;B[bb]C[RIGHT]))",
                SgfEdit.parse("(;SZ[13]C[a ; b (c)]\n (;B[aa])\n (;B[bb]C[RIGHT]) )").write());
        assertEquals("(;C[x \\] y];B[aa])", SgfEdit.parse("(;C[x \\] y];B[aa])").write());
    }

    @Test
    void aLineGoesInAtItsPositionAndNothingElseChanges() {
        SgfEdit edit = SgfEdit.parse(GP62070);
        List<String> at = List.of("B ld", "W le", "B kd", "W lc");
        assertTrue(edit.has(at, List.of("B kd")));
        assertFalse(edit.has(at, List.of("B ka", "W la", "B kd")));
        assertEquals(3, edit.addLine(at, List.of("B ka", "W la", "B kd")));
        // only at the position: the author's other correct line plays the same moves later
        String before = ";W[lc];B[kd]C[RIGHT])";
        int i = GP62070.indexOf(before);
        assertEquals(GP62070.substring(0, i) + ";W[lc](;B[kd]C[RIGHT])(;B[ka];W[la];B[kd]C[RIGHT]))"
                + GP62070.substring(i + before.length()), edit.write());
        assertTrue(edit.has(at, List.of("B ka", "W la", "B kd")));
        // a line already there adds nothing
        assertEquals(0, edit.addLine(at, List.of("B ka", "W la", "B kd")));
    }

    @Test
    void aPositionTheProblemLacksOrALineEndingOnAWrongMoveIsRefused() {
        SgfEdit edit = SgfEdit.parse(GP62070);
        assertNull(edit.at(List.of("B ld", "W lc")));
        assertThrows(IllegalArgumentException.class, () -> edit.addLine(List.of("B ld", "W lc"), List.of("B kd")));
        // B le is on a wrong line at the start: a line may not end there as right
        assertThrows(IllegalArgumentException.class, () -> edit.addLine(List.of(), List.of("B le")));
    }

    @Test
    void aWrongLineGoesOnPastItsRefutationToARightEnd() {
        SgfEdit edit = SgfEdit.parse(GP62070);
        // B le is wrong at the start, W ld its refutation; a line on through it ends right
        assertFalse(edit.has(List.of(), List.of("B le")));
        assertEquals(1, edit.addLine(List.of(), List.of("B le", "W ld", "B kd")));
        assertTrue(GP62070.replace("(;B[le];W[ld])", "(;B[le];W[ld];B[kd]C[RIGHT])").equals(edit.write()), edit.write());
        // and then the move alone is there: a right line goes on from it
        assertTrue(edit.has(List.of(), List.of("B le")));
        assertEquals(0, edit.addLine(List.of(), List.of("B le")));
        // a line already on the author's correct lines, at the start: B ld leads to right
        assertTrue(edit.has(List.of(), List.of("B ld")));
    }

    @Test
    void aNodeGoesInByHandWhereThePositionIs() {
        List<String> at = List.of("B ld", "W le", "B kd", "W lc");
        SgfEdit edit = SgfEdit.parse(GP62070);
        edit.add(at, "B ka", false);
        edit.add(List.of("B ld", "W le", "B kd", "W lc", "B ka"), "W la", false);
        edit.add(List.of("B ld", "W le", "B kd", "W lc", "B ka", "W la"), "B kd", true);
        // node by node, the same as the line in one go
        SgfEdit line = SgfEdit.parse(GP62070);
        line.addLine(at, List.of("B ka", "W la", "B kd"));
        assertEquals(line.write(), edit.write());
        assertThrows(IllegalArgumentException.class, () -> edit.add(List.of("B ld", "W lc"), "B kd", true));
        assertThrows(IllegalArgumentException.class, () -> edit.add(List.of(), "B ld", true));
        assertThrows(IllegalArgumentException.class, () -> edit.add(List.of(), "B zz", true));
        assertEquals(List.of("W le"), edit.at(List.of("B ld")).children().stream().map(SgfEdit.SgfNode::move).toList());
    }

    @Test
    void movesAndRightAreReadFromTheProperties() {
        SgfEdit edit = SgfEdit.parse(GP62070);
        assertEquals("B ka", edit.at(List.of("B ka")).move());
        assertFalse(edit.at(List.of("B ld", "W le", "B ka")).right());
        assertTrue(edit.at(List.of("B ld", "W le", "B kd", "W lc", "B kd")).right());
        assertNull(edit.at(List.of()).move()); // AB on the root is not a move
    }
}
