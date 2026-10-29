package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import autoprob.go.parse.SgfEdit;
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
    void anEditedTreeIsReadBackIntoPositionsAndLines() {
        SuggestionDraft d = SuggestionDraft.build(62070, GP62070, List.of(r19()), 3);
        // unedited, it is the draft as made
        SuggestionDraft same = d.edited(d.suggestedSgf);
        assertEquals(d.addedNodes, same.addedNodes);
        assertEquals(d.places.get(0).lines, same.places.get(0).lines);
        assertEquals(d.places.get(0).moves, same.places.get(0).moves);
        assertEquals(d.comment, same.comment);
        // the line cut short, its new end marked right: the same position, what the finder knew
        // there kept
        List<String> at = d.places.get(0).at;
        SgfEdit cut = SgfEdit.parse(GP62070);
        cut.add(at, "B ka", true);
        SuggestionDraft shorter = d.edited(cut.write());
        assertEquals(1, shorter.addedNodes);
        assertEquals(List.of(List.of("B ka")), shorter.places.get(0).lines);
        assertTrue(shorter.comment.startsWith("After B M10, W M9, B L10, W M11, Black's L13 works too, with the same result as L10. "
                + "Line added: B L13. Found by autoprob"), shorter.comment);
        // every line cut: nothing to add
        SuggestionDraft none = d.edited(GP62070);
        assertEquals(0, none.addedNodes);
        assertTrue(none.places.isEmpty());
    }

    @Test
    void cuttingAMoveLeavesHowSureTheOthersAre() {
        MissingVariationFinder.Finding f = r19();
        Confidence.Evidence sure = new Confidence.Evidence();
        sure.missing = true;
        sure.authorAgrees = true;
        MissingVariationFinder.FoundMove n19 = new MissingVariationFinder.FoundMove();
        n19.move = "B N19";
        n19.lines.add(List.of("B N19"));
        n19.confidence = Confidence.of(sure);
        f.moves.add(n19);
        Confidence r19 = f.moves.get(0).confidence;
        assertNotEquals(r19.low, n19.confidence.low);
        SuggestionDraft d = SuggestionDraft.build(62070, GP62070, List.of(f), 3);
        assertEquals(Math.min(r19.low, n19.confidence.low), d.places.get(0).confidence.low);
        // R19's line cut, N19's (G13 on the problem's board) left
        SgfEdit e = SgfEdit.parse(GP62070);
        e.add(d.places.get(0).at, "B ga", true);
        SuggestionDraft edited = d.edited(e.write());
        assertEquals(List.of("B ga"), edited.places.get(0).moves);
        assertEquals(n19.confidence.low, edited.places.get(0).confidence.low);
    }

    @Test
    void aLineAddedByHandElsewhereMakesAPositionOfItsOwn() {
        SuggestionDraft d = SuggestionDraft.build(62070, GP62070, List.of(r19()), 3);
        SgfEdit e = SgfEdit.parse(d.suggestedSgf);
        e.add(List.of(), "B ma", true);
        // and an answer to a white move the problem lacks, after M10
        e.add(List.of("B ld"), "W lc", false);
        e.add(List.of("B ld", "W lc"), "B le", true);
        SuggestionDraft edited = d.edited(e.write());
        assertEquals(6, edited.addedNodes);
        // the draft's position first, then the new ones in the tree's order
        assertEquals(3, edited.places.size());
        assertEquals(List.of("B ld"), edited.places.get(1).at);
        assertEquals(List.of("W lc"), edited.places.get(1).moves);
        assertEquals(List.of(), edited.places.get(2).at);
        assertEquals(List.of("B ma"), edited.places.get(2).moves);
        assertTrue(edited.comment.contains(" After B M10, White's M11 is answered too. Line added: W M11, B M9. "
                + "At the start, Black's N13 works too. Line added: B N13. Found by autoprob"), edited.comment);
    }

    @Test
    void anEditedLineThroughTheProblemsWrongMoveStillSaysSo() {
        MissingVariationFinder.Finding f = finding(List.of(), List.of("B S16", "B R19"), "B S15", List.of("B S15", "W S16", "B R16"));
        f.moves.get(0).inFileAsWrong = true;
        SuggestionDraft d = SuggestionDraft.build(62070, GP62070, List.of(f), 3);
        SuggestionDraft same = d.edited(d.suggestedSgf);
        assertEquals(List.of("B le"), same.places.get(0).markedWrong);
        assertEquals(d.comment, same.comment);
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
