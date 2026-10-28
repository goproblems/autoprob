package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import autoprob.api.Problem;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.parse.Parser;

class MissingVariationFinderTest {
    // black plays A19; white answers B18 or B19, each with a correct reply; C19 is wrong
    private static final String SGF = "(;SZ[19]AB[dd]AW[ee]"
            + "(;B[aa](;W[bb];B[cc]C[RIGHT])(;W[ba];B[ab]C[RIGHT]))"
            + "(;B[ca]C[WRONG]))";

    @Test
    void solverPositionsOnTheCorrectLinesRootFirst() throws Exception {
        Node root = new Parser().parse(SGF);
        List<MissingVariationFinder.Position> positions =
                MissingVariationFinder.authorPositions(MovePath.extractRightPaths(root), "B");
        assertEquals(3, positions.size());
        assertEquals(List.of(), positions.get(0).at());
        assertEquals(Set.of("B A19"), positions.get(0).correct());
        assertEquals(List.of("B A19", "W B18"), positions.get(1).at());
        assertEquals(Set.of("B C17"), positions.get(1).correct());
        assertEquals(List.of("B A19", "W B19"), positions.get(2).at());
        assertEquals(Set.of("B A18"), positions.get(2).correct());
        // the author's lines from each position
        assertEquals(List.of(List.of("B A19", "W B18", "B C17"), List.of("B A19", "W B19", "B A18")),
                positions.get(0).lines());
        assertEquals(List.of(List.of("B C17")), positions.get(1).lines());
    }

    @Test
    void playingElsewhereIsAMoveInTheSolversOwnFill() {
        ProblemIsolator.IsolatedProblem iso = StatusJudgeTest.sekiProblem();
        String away = MissingVariationFinder.elsewhere(iso);
        assertTrue(away.startsWith("B "), away);
        Point p = Intersection.gtp2point(away.substring(2));
        assertFalse(RegimeWalker.zone(iso)[p.x][p.y], "outside the problem area");
        // a point of black's own fill, the last: the ko exchanges take the first
        List<Point> own = RegimeWalker.neutralPoints(iso, RegimeWalker.zone(iso), Intersection.BLACK);
        assertEquals(own.get(own.size() - 1), p);
    }

    @Test
    void theFileMovesIncludeWrongLines() throws Exception {
        Node root = new Parser().parse(SGF);
        assertEquals(Set.of("B A19", "B C19"), MissingVariationFinder.sgfMoves(root, List.of()));
        assertEquals(Set.of("W B18", "W B19"), MissingVariationFinder.sgfMoves(root, List.of("B A19")));
        assertEquals(Set.of(), MissingVariationFinder.sgfMoves(root, List.of("B T1")));
    }

    @Test
    void notthisOutlawsAMoveAndForceLimitsThePosition() throws Exception {
        // gp-62070's tree, in short: after B A19, W B18, black's B19 is outlawed there; the
        // other answer, W B19, is forced
        Node root = new Parser().parse("(;SZ[19]AB[dd]AW[ee](;B[aa](;W[bb](;B[cc]C[RIGHT])(;B[ba]C[NOTTHIS]))"
                + "(;W[ba]C[FORCE];B[ab]C[RIGHT])))");
        assertEquals(Set.of("B B19"), MissingVariationFinder.outlawed(root, List.of("B A19", "W B18")));
        assertEquals(Set.of("B C17", "B B19"), MissingVariationFinder.sgfMoves(root, List.of("B A19", "W B18")));
        assertEquals(Set.of(), MissingVariationFinder.outlawed(root, List.of()));
        assertFalse(MissingVariationFinder.forced(root, List.of("B A19", "W B18")));
        assertTrue(MissingVariationFinder.forced(root, List.of("B A19", "W B19")));
    }

    @Test
    void theAuthorsMovesElsewhereOnItsCorrectLines() throws Exception {
        Node root = new Parser().parse(SGF);
        assertEquals(Set.of("B C17", "B A18"), MissingVariationFinder.authorMovesElsewhere(root, List.of(), "B"));
        assertEquals(Set.of("B A19", "B A18"), MissingVariationFinder.authorMovesElsewhere(root, List.of("B A19", "W B18"), "B"));
    }

    @Test
    void aKoTakeAndAMoveOnTheOutside() throws Exception {
        ProblemIsolator.IsolatedProblem iso = new ProblemIsolator.IsolatedProblem();
        iso.problem = new Node(null);
        iso.problem.defaultToMoveColor = autoprob.go.Intersection.BLACK;
        // a ko on the top edge: black's C18 takes white's B18; and a lone white stone at K10
        for (String b : new String[]{"B19", "A18", "B17"}) put(iso, b, autoprob.go.Intersection.BLACK);
        for (String w : new String[]{"C19", "D18", "C17", "B18", "K10"}) put(iso, w, autoprob.go.Intersection.WHITE);
        assertTrue(MissingVariationFinder.takesKo(iso.problem, autoprob.go.Intersection.gtp2point("C18")));
        assertFalse(MissingVariationFinder.takesKo(iso.problem, autoprob.go.Intersection.gtp2point("L10")));
        // beside the lone stone, three of the four directions run open to the edge
        java.awt.Point beside = autoprob.go.Intersection.gtp2point("L10");
        assertEquals(3, MissingVariationFinder.emptyShots(iso, iso.problem, beside));
        assertEquals(1.0, MissingVariationFinder.nearestStone(iso, iso.problem, beside), 1e-9);
        // the ko point is shut in on every side
        assertEquals(0, MissingVariationFinder.emptyShots(iso, iso.problem, autoprob.go.Intersection.gtp2point("C18")));
    }

    private static void put(ProblemIsolator.IsolatedProblem iso, String gtp, int color) {
        java.awt.Point q = autoprob.go.Intersection.gtp2point(gtp);
        iso.problem.board.board[q.x][q.y].stone = color;
    }

    @Test
    void onlyStandardLifeAndDeathProblemsAreChecked() {
        Problem p = new Problem();
        p.genre = "tesuji";
        p.isStandard = true;
        p.sgf = SGF;
        assertEquals("genre tesuji", MissingVariationFinder.unsuitable(p));
        p.genre = "life and death";
        p.isStandard = false;
        assertEquals("not marked standard", MissingVariationFinder.unsuitable(p));
        p.isStandard = true;
        assertNull(MissingVariationFinder.unsuitable(p));
        p.sgf = " ";
        assertEquals("no sgf", MissingVariationFinder.unsuitable(p));
    }
}
