package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import autoprob.go.parse.Parser;

class ProblemRedoerTest {
    @Test
    void readsTheDifficultyFromTheRootOnly() throws Exception {
        String sgf = "(\r\n;DI[3 dan]SZ[19]AB[dd]\r\n(;B[cc]C[RIGHT])(;B[ee];W[cc]DI[5 kyu]))";
        assertEquals("3 dan", ProblemRedoer.difficulty(new Parser().parse(sgf)));
    }

    @Test
    void noDifficultyWithoutTheProperty() throws Exception {
        assertNull(ProblemRedoer.difficulty(new Parser().parse("(;SZ[19]AB[dd];B[cc]C[RIGHT])")));
        assertNull(ProblemRedoer.difficulty(new Parser().parse("(;DI[ ]SZ[19]AB[dd];B[cc]C[RIGHT])")));
    }
}
