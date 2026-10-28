package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import autoprob.go.parse.SgfEditTest;
import autoprob.go.parse.SmallBoard;

class SuggestionDraftTest {
    static final String GP62070 = SgfEditTest.GP62070;

    // a finding as the finder writes it, on 19x19 (gp-62070 is a 13x13 board played with
    // columns +6)
    static MissingVariationFinder.Finding finding(List<String> at, List<String> accepted, String move, List<String> line) {
        MissingVariationFinder.Finding f = new MissingVariationFinder.Finding();
        f.problem = "gp-62070";
        f.difficulty = "18 kyu";
        f.at = at;
        f.accepted.addAll(accepted);
        f.level = MoveClassifier.Level.CLEAN;
        MissingVariationFinder.FoundMove m = new MissingVariationFinder.FoundMove();
        m.move = move;
        m.lines.add(line);
        m.confidence = Confidence.of(new Confidence.Evidence());
        f.moves.add(m);
        return f;
    }

    static MissingVariationFinder.Finding r19() {
        return finding(List.of("B S16", "W S15", "B R16", "W S17"), List.of("B R16"), "B R19", List.of("B R19", "W S19", "B R16"));
    }

    @Test
    void movesGoBackToTheProblemsOwnBoard() {
        SmallBoard.Embedded board = SmallBoard.toNineteen(GP62070);
        assertEquals(6, board.dx());
        assertEquals(0, board.dy());
        assertEquals(List.of("B ld", "W le", "B kd", "W lc"), SuggestionDraft.onBoard(List.of("B S16", "W S15", "B R16", "W S17"), board));
        assertThrows(IllegalArgumentException.class, () -> SuggestionDraft.onBoard(List.of("B A1"), board));
    }

    @Test
    void theDraftAddsTheLineAndSaysWhereWhatAndHowSure() {
        SuggestionDraft d = SuggestionDraft.build(62070, GP62070, List.of(r19()), 3);
        assertEquals(3, d.addedNodes);
        assertEquals(13, d.boardSize);
        assertEquals(3, d.version);
        assertEquals("18 kyu", d.difficulty);
        assertEquals(List.of("B M10", "W M9", "B L10", "W M11"), d.points(d.places.get(0).at));
        assertTrue(d.suggestedSgf.contains(";W[lc](;B[kd]C[RIGHT])(;B[ka];W[la];B[kd]C[RIGHT]))"), d.suggestedSgf);
        assertTrue(d.comment.startsWith("After B M10, W M9, B L10, W M11, Black's L13 works too, with the same result as L10. "
                + "Line added: B L13, W M13, B L10. Found by autoprob, an automated checker (confidence: "), d.comment);
        assertEquals(GP62070, d.payload(d.comment).get("originalSgf").getAsString());
    }

    @Test
    void positionsAtTheStartAndDeeperGoInOneSuggestion() {
        // N13 at the start, an empty point (made up), with the deeper R19
        MissingVariationFinder.Finding start = finding(List.of(), List.of("B S16", "B R19"), "B T19", List.of("B T19"));
        SuggestionDraft d = SuggestionDraft.build(62070, GP62070, List.of(start, r19()), 3);
        assertEquals(4, d.addedNodes);
        assertEquals(2, d.places.size());
        assertTrue(d.suggestedSgf.endsWith("(;B[id];W[le];B[kd];W[ld])(;B[ma]C[RIGHT]))"), d.suggestedSgf);
        assertTrue(d.comment.startsWith("At the start, Black's N13 works too, with the same result as M10 and L13. Line added: B N13. "
                + "After B M10, W M9, B L10, W M11, Black's L13 works too"), d.comment);
    }

    @Test
    void aMoveTheProblemHasAsWrongGoesOnPastTheRefutationAndSaysSo() {
        // M9 (S15 on 19x19) is wrong at the start, W M10 its refutation. the finder's lines: the
        // move alone, and on through the refutation; the longer goes in first, which leaves
        // the move alone there already
        MissingVariationFinder.Finding f = finding(List.of(), List.of("B S16", "B R19"), "B S15", List.of("B S15"));
        f.moves.get(0).lines.add(List.of("B S15", "W S16", "B R16"));
        f.moves.get(0).inFileAsWrong = true;
        List<String> notes = new java.util.ArrayList<>();
        SuggestionDraft d = SuggestionDraft.build(62070, GP62070, List.of(f), 3, notes);
        assertEquals(List.of(), notes);
        assertEquals(1, d.addedNodes);
        assertEquals(GP62070.replace("(;B[le];W[ld])", "(;B[le];W[ld];B[kd]C[RIGHT])"), d.suggestedSgf);
        assertTrue(d.comment.startsWith("At the start, Black's M9 works too (the problem has M9 as wrong), with the same result as M10 and L13."),
                d.comment);
        // the move alone, ending on the author's wrong line, cannot be suggested: it says why
        MissingVariationFinder.Finding alone = finding(List.of(), List.of("B S16"), "B S15", List.of("B S15"));
        assertNull(SuggestionDraft.build(62070, GP62070, List.of(alone), 3, notes));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0).contains("on a wrong line"), notes.get(0));
    }

    @Test
    void linesTheProblemHasAlreadyMakeNoSuggestion() {
        // the author's own retake at R16
        MissingVariationFinder.Finding own = finding(List.of("B S16", "W S15", "B R16", "W S17"), List.of("B R16"), "B R16", List.of("B R16"));
        assertNull(SuggestionDraft.build(62070, GP62070, List.of(own), 3));
        MissingVariationFinder.Finding none = r19();
        none.moves.clear();
        assertNull(SuggestionDraft.build(62070, GP62070, List.of(none), 3));
    }

    @Test
    void theProblemIsTheSameButForTheDifficultyOurDownloadsAdd() {
        String local = GP62070.replace("(;FF[4]", "(;DI[18 kyu]FF[4]");
        assertTrue(SuggestionDraft.sameProblem(GP62070, local));
        assertFalse(SuggestionDraft.sameProblem(GP62070, local.replace("C[NOTTHIS]", "")));
    }
}
