package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import autoprob.go.parse.SgfEditTest;
import autoprob.go.parse.SmallBoard;

class SuggestionSubmitterTest {
    @Test
    void movesGoBackToTheProblemsOwnBoard() {
        // gp-62070 is a 13x13 board the finder plays with columns +6
        SmallBoard.Embedded board = SmallBoard.toNineteen(SgfEditTest.GP62070);
        assertEquals(6, board.dx());
        assertEquals(0, board.dy());
        List<String> at = SuggestionSubmitter.onBoard(List.of("B S16", "W S15", "B R16", "W S17"), board);
        assertEquals(List.of("B ld", "W le", "B kd", "W lc"), at);
        assertEquals(List.of("B M10", "W M9", "B L10", "W M11"), SuggestionSubmitter.points(at, board));
        assertThrows(IllegalArgumentException.class, () -> SuggestionSubmitter.onBoard(List.of("B A1"), board));
    }

    @Test
    void theCommentSaysWhereWhatAndHowSure() {
        SmallBoard.Embedded board = SmallBoard.toNineteen(SgfEditTest.GP62070);
        MissingVariationFinder.Finding f = new MissingVariationFinder.Finding();
        f.problem = "gp-62070";
        f.at = List.of("B S16", "W S15", "B R16", "W S17");
        f.accepted.add("B R16");
        MissingVariationFinder.FoundMove m = new MissingVariationFinder.FoundMove();
        m.move = "B R19";
        m.confidence = Confidence.of(new Confidence.Evidence());
        f.moves.add(m);
        List<String> at = SuggestionSubmitter.onBoard(f.at, board);
        List<List<String>> lines = List.of(SuggestionSubmitter.onBoard(List.of("B R19", "W S19", "B R16"), board));
        String c = SuggestionSubmitter.comment(f, at, lines, board);
        assertTrue(c.startsWith("After B M10, W M9, B L10, W M11, Black's L13 works too, with the same result as L10. "
                + "Line added: B L13, W M13, B L10. Found by autoprob, an automated checker (confidence: "), c);
    }

    @Test
    void theProblemIsTheSameButForTheDifficultyOurDownloadsAdd() {
        String local = SgfEditTest.GP62070.replace("(;FF[4]", "(;DI[18 kyu]FF[4]");
        assertTrue(SuggestionSubmitter.sameProblem(SgfEditTest.GP62070, local));
        assertFalse(SuggestionSubmitter.sameProblem(SgfEditTest.GP62070, local.replace("C[NOTTHIS]", "")));
    }
}
