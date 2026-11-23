package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

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
    void aLineOnTheProblemsTreeTakesItsOwnAnswers() throws Exception {
        // gp-62134 in short: the problem has black's A19 as wrong, answered by W B18 and then,
        // after black's C17, by W C18. a line from A19 meets those answers on the problem's tree,
        // the ones the site's computer plays, and none once it leaves the tree
        Node root = new Parser().parse("(;SZ[19]AB[dd]AW[ee](;B[aa];W[bb](;B[cc];W[cb])(;B[ab];W[ba]))(;B[ca]C[RIGHT]))");
        Function<List<String>, Collection<String>> own = MissingVariationFinder.ownAnswers(root, List.of());
        assertEquals(Set.of("W B18"), own.apply(List.of("B A19")));
        assertEquals(Set.of("W C18"), own.apply(List.of("B A19", "W B18", "B C17")));
        assertEquals(Set.of(), own.apply(List.of("B A19", "W B18", "B F14")));
        assertEquals(Set.of(), own.apply(List.of("B C19")));
        // from a position further on, its moves come first
        assertEquals(Set.of("W C18"), MissingVariationFinder.ownAnswers(root, List.of("B A19", "W B18")).apply(List.of("B C17")));
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

    // gp-62839 turned into the top right corner on the fortress board: black kills white's
    // corner, whose stones are at stake. the author's answer is P17 (E3 there)
    static ProblemIsolator.IsolatedProblem throwInProblem() {
        ProblemIsolator.IsolatedProblem iso = new ProblemIsolator.IsolatedProblem();
        iso.problem = new Node(null);
        String[] white = {"Q19", "S19", "P18", "R18", "S18", "Q17", "Q16"};
        for (String w : white) put(iso, w, Intersection.WHITE);
        for (String b : new String[]{"T18", "O18", "O17", "R17", "S17", "P16", "P15", "Q15", "R15"}) put(iso, b, Intersection.BLACK);
        new BalancedFortress().build(iso.problem.board, 4, iso.filledStones);
        iso.solverColor = Intersection.BLACK;
        iso.problem.defaultToMoveColor = Intersection.BLACK;
        for (String w : white) iso.stakes.add(Intersection.gtp2point(w));
        return iso;
    }

    @Test
    void aStoneTheAnswerCapturesAtOnceIsNoAnswerOfItsOwn() throws Exception {
        ProblemIsolator.IsolatedProblem iso = throwInProblem();
        // black's throw-in at P19 (E1 there), which white's O19 captures, before the author's P17
        String why = MissingVariationFinder.insertedExchange(iso, iso.problem, List.of("B P19", "W O19", "B P17"), Set.of("B P17"));
        assertTrue(why != null && why.contains("captured at once by O19"), why);
        // before a move that does not work there anyway, it is a line of its own
        assertNull(MissingVariationFinder.insertedExchange(iso, iso.problem, List.of("B P19", "W O19", "B P17"), Set.of("B T19")));
        assertTrue(iso.problem.babies.isEmpty(), "the position is left as it was");
    }

    @Test
    void anExchangeOutsideTheFightIsNoAnswerOfItsOwn() throws Exception {
        ProblemIsolator.IsolatedProblem iso = throwInProblem();
        // a peep out in the open ground, answered there: neither stone touches white's at stake
        assertFalse(MissingVariationFinder.inFight(iso, iso.problem, Intersection.gtp2point("N16")));
        String why = MissingVariationFinder.insertedExchange(iso, iso.problem, List.of("B N16", "W N17", "B P17"), Set.of("B P17"));
        assertTrue(why != null && why.contains("outside the fight"), why);
        // a move next to them is in the fight, another order of play before the author's move
        assertTrue(MissingVariationFinder.inFight(iso, iso.problem, Intersection.gtp2point("P17")));
        assertNull(MissingVariationFinder.insertedExchange(iso, iso.problem, List.of("B P17", "W N19", "B N17"), Set.of("B N17")));
        // and a line that ends before the solver's next move puts nothing in before anything
        assertNull(MissingVariationFinder.insertedExchange(iso, iso.problem, List.of("B N16", "W N17"), Set.of("B P17")));
    }

    @Test
    void onlyASolverWithStonesAtStakeHasAnInsideToStayIn() {
        // black kills white's corner: black's stones are safe outside
        ProblemIsolator.IsolatedProblem iso = throwInProblem();
        assertFalse(MissingVariationFinder.defends(iso));
        // with a black stone at stake too, black's group is part of what the problem decides
        iso.stakes.add(Intersection.gtp2point("P16"));
        assertTrue(MissingVariationFinder.defends(iso));
    }

    @Test
    void aProblemsResultIsSaidAsItsGoal() {
        // reached outright, black kills white's corner; with black's own stones at stake it lives
        ProblemIsolator.IsolatedProblem iso = throwInProblem();
        assertEquals("kill", MissingVariationFinder.result(MoveClassifier.Level.CLEAN, iso));
        assertEquals("ko", MissingVariationFinder.result(MoveClassifier.Level.KO, iso));
        assertEquals("seki", MissingVariationFinder.result(MoveClassifier.Level.SEKI, iso));
        assertNull(MissingVariationFinder.result(MoveClassifier.Level.FAIL, iso));
        iso.stakes.add(Intersection.gtp2point("P16"));
        assertEquals("live", MissingVariationFinder.result(MoveClassifier.Level.CLEAN, iso));
    }

    @Test
    void aPointsOwnerIsSeenFromTheMover() {
        // after the best move, black owns A1 and white T19
        autoprob.katastruct.KataAnalysisResult after = new autoprob.katastruct.KataAnalysisResult();
        after.ownership = new java.util.ArrayList<>(java.util.Collections.nCopies(361, 0.0));
        Point a1 = Intersection.gtp2point("A1"), t19 = Intersection.gtp2point("T19");
        after.ownership.set(a1.x + a1.y * 19, 0.97);
        after.ownership.set(t19.x + t19.y * 19, -0.9);
        assertEquals(0.97, DecisionFeatureExtractor.pointOwner("B A1", after));
        assertEquals(-0.9, DecisionFeatureExtractor.pointOwner("B T19", after));
        assertEquals(0.9, DecisionFeatureExtractor.pointOwner("W T19", after));
        assertNull(DecisionFeatureExtractor.pointOwner("B A1", null));
    }

    @Test
    void aLineThatRunsOutOfTheProblemIsNoSuggestion() throws Exception {
        // stones from A1 to C3, and the author's moves out to D1 and A4, on a wrong line too
        Node root = new Parser().parse("(;SZ[19]AB[as][bs][br]AW[cs][cr][cq](;B[ds];W[ap])(;B[bq]C[RIGHT]))");
        assertArrayEquals(new int[]{0, 15, 3, 18}, MissingVariationFinder.area(root));
        // a line past the problem by a line, on the side, above or both, stays in it
        assertNull(MissingVariationFinder.leaksOut(root, List.of(List.of("B E1", "W B5", "B E5"))));
        // two lines past it runs out of it, in any line of the move's
        String why = MissingVariationFinder.leaksOut(root, List.of(List.of("B B3"), List.of("B E1", "W F1")));
        assertTrue(why != null && why.contains("to F1, 2 lines past"), why);
        why = MissingVariationFinder.leaksOut(root, List.of(List.of("B B3", "W A6")));
        assertTrue(why != null && why.contains("to A6"), why);
    }

    @Test
    void everyMoveThatWorksIsHeldToItsLineTheFoundMoveStaying() throws Exception {
        // gp-62299 after its found S19 (M13 on the problem's 13x13 board): after W S17, B S16,
        // W R15, black's T17 and S18 work as well as the line's S15
        MissingVariationFinder.Position pos = new MissingVariationFinder.Position(List.of(), Set.of("B S16", "B S17"), List.of());
        DecisionRecord d = new DecisionRecord();
        d.at = List.of("B S19", "W S17", "B S16", "W R15");
        d.role = DecisionRecord.SOLVER;
        d.chose = "B S15";
        d.alternatives = List.of("B T17", "B S18");
        Set<List<String>> others = MissingVariationFinder.otherMoves(pos, List.of(d));
        assertEquals(Set.of(List.of("B S19", "W S17", "B S16", "W R15", "B T17"), List.of("B S19", "W S17", "B S16", "W R15", "B S18")), others);
        List<String> main = List.of("B S19", "W S17", "B S16", "W R15", "B S15", "W T17", "B S14");
        List<String> t17 = List.of("B S19", "W S17", "B S16", "W R15", "B T17", "W T16", "B S18");
        List<String> s18 = List.of("B S19", "W S17", "B S16", "W R15", "B S18", "W T17", "B T18");
        List<String> s18b = List.of("B S19", "W S17", "B S16", "W R15", "B S18", "W S15", "B T17");
        // a line through S18 that fails: S18 goes with everything after it
        List<List<String>> lines = new java.util.ArrayList<>(List.of(main, t17, s18, s18b));
        assertNull(MissingVariationFinder.check("at the start", lines, others, l -> l.contains("W S15") ? "falls short" : null));
        assertEquals(List.of(main, t17), lines);
        // the found move's own line failing is the found move's failure
        assertEquals("falls short", MissingVariationFinder.check("at the start", lines, others, l -> l.equals(main) ? "falls short" : null));
    }

    @Test
    void aLineALimitStoppedGoesInAsItIsSayingWhy() {
        // gp-62299 after its found S19, the generator started after S19 and W S17 (made up): it
        // stopped two branches at its limit of decisions, the second of them past a point the
        // finder's line ends at anyway (where a ko starts, say), so only the first is cut short
        List<String> t17 = List.of("B S19", "W S17", "B S16", "W R15", "B T17", "W T16", "B S18");
        List<String> s18 = List.of("B S19", "W S17", "B S16", "W R15", "B S18");
        List<SolutionPathGenerator.Cut> cuts = List.of(
                new SolutionPathGenerator.Cut(t17.subList(2, t17.size()), "reached the tree limit of 60 decisions"),
                new SolutionPathGenerator.Cut(List.of("B S16", "W R15", "B S18", "W T17", "B T18"), "reached the tree limit of 60 decisions"));
        List<MissingVariationFinder.Cut> cut = MissingVariationFinder.cutLines(List.of("B S19", "W S17"), cuts, List.of(t17, s18));
        assertEquals(1, cut.size());
        assertEquals(t17, cut.get(0).line);
        assertEquals("reached the tree limit of 60 decisions", cut.get(0).why);
        // its end walked out short of the move's level, it stays with a note; any other line
        // fails as before
        assertTrue(MissingVariationFinder.noteIfCut(cut, t17, "walked out from there it comes to ko, short of clean"));
        assertEquals("reached the tree limit of 60 decisions; walked out from there it comes to ko, short of clean", cut.get(0).why);
        assertFalse(MissingVariationFinder.noteIfCut(cut, s18, "walked out from there it comes to ko, short of clean"));
    }

    @Test
    void aLineRunsNoDeeperThanTheProblemsOwnAndSevenMovesAtLeast() {
        // the problem's own lines from the position are one and three moves long
        MissingVariationFinder.Position shallow = new MissingVariationFinder.Position(List.of(), Set.of("B A19"),
                List.of(List.of("B A19"), List.of("B A19", "W B18", "B C17")));
        assertEquals(7, MissingVariationFinder.lineDepth(shallow));
        // nine moves long: two more, on a solver's move
        List<String> nine = List.of("B A1", "W A2", "B A3", "W A4", "B A5", "W A6", "B A7", "W A8", "B A9");
        assertEquals(11, MissingVariationFinder.lineDepth(new MissingVariationFinder.Position(List.of(), Set.of("B A1"), List.of(nine))));
        // a line of the problem's that ends on the opponent's move
        assertEquals(11, MissingVariationFinder.lineDepth(new MissingVariationFinder.Position(List.of(), Set.of("B A1"),
                List.of(nine.subList(0, 8)))));
        // a line the depth stopped says so in words for a review
        List<String> line = List.of("B S19", "W S17", "B S16", "W R15", "B T17", "W T16", "B S18");
        List<MissingVariationFinder.Cut> cut = MissingVariationFinder.cutLines(List.of(),
                List.of(new SolutionPathGenerator.Cut(line, "hit max moves (7)")), List.of(line));
        assertEquals("as long as a line added to this position goes, 7 moves", cut.get(0).why);
    }

    @Test
    void theAuthorsPositionsSayWhereALineEndsOrGoesOn() throws Exception {
        // the problem: black A19, then W B18, B C17 or W B19, B A18
        Node root = new Parser().parse(SGF);
        ProblemIsolator.IsolatedProblem iso = new ProblemIsolator.IsolatedProblem();
        iso.problem = new Node(null);
        put(iso, "D16", Intersection.BLACK);
        put(iso, "E15", Intersection.WHITE);
        iso.solverColor = Intersection.BLACK;
        iso.problem.defaultToMoveColor = Intersection.BLACK;
        java.util.Map<String, Set<String>> spots = MissingVariationFinder.authorSpots(iso, root);
        assertTrue(iso.problem.babies.isEmpty(), "the position is left as it was");
        assertEquals(3, spots.size());
        // after black's first move the author has two answers; its lines end after the next
        assertEquals(Set.of("W B18", "W B19"), spots.get(Positions.key(Positions.play(iso, List.of("B A19")), iso)));
        assertEquals(Set.of(), spots.get(Positions.key(Positions.play(iso, List.of("B A19", "W B18", "B C17")), iso)));
        // the same position by another order of moves is the same spot
        assertEquals(Set.of(), spots.get(Positions.key(Positions.play(iso, List.of("B C17", "W B18", "B A19")), iso)));
        // a position the author's correct lines do not reach, or reach with the other side to play
        assertNull(spots.get(Positions.key(Positions.play(iso, List.of("B C19")), iso)));
        assertNull(spots.get(Positions.key(Positions.play(iso, List.of("B A19", "W B18")), iso)));
    }

    @Test
    void aMoveElsewhereWhileTheAnswerTakesTheKoOnlySpendsAThreat() throws Exception {
        ProblemIsolator.IsolatedProblem iso = new ProblemIsolator.IsolatedProblem();
        iso.problem = new Node(null);
        iso.problem.defaultToMoveColor = Intersection.BLACK;
        // a ko on the top edge: black's C18 takes white's B18
        for (String b : new String[]{"B19", "A18", "B17"}) put(iso, b, Intersection.BLACK);
        for (String w : new String[]{"C19", "D18", "C17", "B18", "K10"}) put(iso, w, Intersection.WHITE);
        MoveClassifier.Verdict take = verdict("B C18", MoveClassifier.Level.KO), other = verdict("B L10", MoveClassifier.Level.KO);
        java.util.Map<String, MoveClassifier.Verdict> verdicts = java.util.Map.of("B C18", take, "B L10", other);
        // gp-61191: the author takes the ko, the found move plays elsewhere first
        MissingVariationFinder.Position pos = new MissingVariationFinder.Position(List.of(), Set.of("B C18"), List.of());
        String why = MissingVariationFinder.leavesKo(iso.problem, pos, verdicts, other);
        assertTrue(why != null && why.contains("C18 takes the ko"), why);
        // a found move that takes the ko itself, or an author's answer that does not, is no such thing
        assertNull(MissingVariationFinder.leavesKo(iso.problem, pos, verdicts, take));
        assertNull(MissingVariationFinder.leavesKo(iso.problem, new MissingVariationFinder.Position(List.of(), Set.of("B L10"), List.of()),
                verdicts, verdict("B M10", MoveClassifier.Level.KO)));
        // gp-62278: the author takes the ko or plays elsewhere first. one answer that takes it
        // is enough: the site rejected the found H1 there, which did not ("cannot guarantee
        // Black a clean kill when white tenukis")
        MissingVariationFinder.Position either = new MissingVariationFinder.Position(List.of(), Set.of("B C18", "B L10"), List.of());
        assertTrue(MissingVariationFinder.leavesKo(iso.problem, either, verdicts, verdict("B M10", MoveClassifier.Level.KO)) != null);
        // an accepted move that takes the ko but falls short of it is no such answer
        java.util.Map<String, MoveClassifier.Verdict> shortOf = java.util.Map.of("B C18", verdict("B C18", MoveClassifier.Level.FAIL), "B L10", other);
        assertNull(MissingVariationFinder.leavesKo(iso.problem, either, shortOf, verdict("B M10", MoveClassifier.Level.KO)));
    }

    @Test
    void aKoThatCostsPointsOnTheRawBoardIsAWorseKo() {
        // gp-62668's N19 came to 5.0 points less than the author's M19 there, and the site
        // rejected it: "White can tenuki, and Black can only kill this W group by ko"
        String why = MissingVariationFinder.worseByPoints(38.6, 43.6, 4);
        assertTrue(why != null && why.contains("5.0 points less") && why.contains("worse ko"), why);
        // gp-60804's A3, which the site kept, came to 3.7 less
        assertNull(MissingVariationFinder.worseByPoints(1.4, 5.1, 4));
        // a move that comes to more than the author's, and a position where the points are
        // not asked about (the best is clean), or where katago had none for the author's moves
        assertNull(MissingVariationFinder.worseByPoints(8.0, 5.0, 4));
        assertNull(MissingVariationFinder.worseByPoints(-20.0, 5.0, 0));
        assertNull(MissingVariationFinder.worseByPoints(-20.0, null, 4));
    }

    @Test
    void aPositionIsHeldToWhatItsLineCameTo() {
        // gp-61250: B E2 read as a seki at the start, the line through it as a ko further down
        java.util.Map<String, MoveClassifier.Level> reached = new java.util.HashMap<>();
        MissingVariationFinder.Position start = new MissingVariationFinder.Position(List.of(), Set.of("B E2"), List.of());
        assertNull(MissingVariationFinder.lineLevel(reached, start.at()));
        MissingVariationFinder.carry(reached, start, null, java.util.Map.of("B E2", verdict("B E2", MoveClassifier.Level.SEKI)));
        List<String> two = List.of("B E2", "W F2");
        assertEquals(MoveClassifier.Level.SEKI, MissingVariationFinder.lineLevel(reached, two));
        // a line the problem does not have is held to nothing
        assertNull(MissingVariationFinder.lineLevel(reached, List.of("B A2", "W B2")));
        // a position read below its line passes on what the line was due, not what it read
        MissingVariationFinder.Position second = new MissingVariationFinder.Position(two, Set.of("B A2"), List.of());
        MissingVariationFinder.carry(reached, second, MoveClassifier.Level.SEKI, null);
        List<String> four = List.of("B E2", "W F2", "B A2", "W B2");
        assertEquals(MoveClassifier.Level.SEKI, MissingVariationFinder.lineLevel(reached, four));
        // one read as well as its line passes on what its own moves came to there
        MissingVariationFinder.carry(reached, second, MoveClassifier.Level.SEKI,
                java.util.Map.of("B A2", verdict("B A2", MoveClassifier.Level.CLEAN)));
        assertEquals(MoveClassifier.Level.CLEAN, MissingVariationFinder.lineLevel(reached, four));
        // with nothing due and nothing read, nothing is passed on
        java.util.Map<String, MoveClassifier.Level> none = new java.util.HashMap<>();
        MissingVariationFinder.carry(none, start, null, null);
        assertTrue(none.isEmpty());
    }

    @Test
    void aCleanLineWhereTheSolverTakesAKoShowsAKo() throws Exception {
        ProblemIsolator.IsolatedProblem iso = new ProblemIsolator.IsolatedProblem();
        iso.problem = new Node(null);
        iso.problem.defaultToMoveColor = Intersection.BLACK;
        // a ko on the top edge: black's C18 takes white's B18
        for (String b : new String[]{"B19", "A18", "B17"}) put(iso, b, Intersection.BLACK);
        for (String w : new String[]{"C19", "D18", "C17", "B18", "K10"}) put(iso, w, Intersection.WHITE);
        String why = MissingVariationFinder.takesKoOnLine(iso.problem, List.of("B L10", "W L11", "B C18"));
        assertTrue(why != null && why.contains("takes a ko, at C18"), why);
        assertNull(MissingVariationFinder.takesKoOnLine(iso.problem, List.of("B L10", "W L11", "B M10")));
        // the found move itself may take one, and so may the opponent
        assertNull(MissingVariationFinder.takesKoOnLine(iso.problem, List.of("B C18")));
        assertNull(MissingVariationFinder.takesKoOnLine(iso.problem, List.of("B C18", "W L10", "B L11", "W B18", "B M10")));
        assertTrue(iso.problem.babies.isEmpty(), "the position is left as it was");
    }

    private static MoveClassifier.Verdict verdict(String move, MoveClassifier.Level level) {
        MoveClassifier.Verdict v = new MoveClassifier.Verdict();
        v.move = move;
        v.level = level;
        return v;
    }

    @Test
    void theReadingAndTheAuthorDisagreeWhenEveryAcceptedMoveFallsShort() {
        List<MoveClassifier.Verdict> wrong = List.of(verdict("B S19", MoveClassifier.Level.KO));
        // gp-60421: the author's one answer came out a ko under a found move's clean kill
        assertTrue(MissingVariationFinder.allFallShort(Set.of("B S19"), wrong));
        // one of two answers reaching the best, the author is only partly wrong
        assertFalse(MissingVariationFinder.allFallShort(Set.of("B S19", "B T18"), wrong));
        assertFalse(MissingVariationFinder.allFallShort(Set.of("B S19"), List.of()));
        assertFalse(MissingVariationFinder.allFallShort(Set.of(), wrong));
    }

    // black's C19 is wrong: white answers C18, and then black's D19 is answered at D18, while
    // E19 has nothing after it
    private static final String REFUTED = "(;SZ[19]AB[dd]AW[ee]"
            + "(;B[aa]C[RIGHT])"
            + "(;B[ca];W[cb](;B[da];W[db])(;B[ea])))";

    @Test
    void aMoveTheProblemAnswersItselfIsToldFromOneItOnlyHas() throws Exception {
        Node root = new Parser().parse(REFUTED);
        Function<List<String>, Collection<String>> own = MissingVariationFinder.ownAnswers(root, List.of());
        // D19 the problem answers; E19 it has with no answer, and F19 it lacks
        assertEquals(List.of("B D19"), SolutionPathGenerator.answered(List.of("B C19", "W C18"),
                List.of("B D19", "B E19", "B F19"), own));
        // off the problem's tree nothing is answered
        assertEquals(List.of(), SolutionPathGenerator.answered(List.of("B C19", "W B18"), List.of("B D19"), own));
        // and from a position down the tree, the same
        Function<List<String>, Collection<String>> deeper = MissingVariationFinder.ownAnswers(root, List.of("B C19", "W C18"));
        assertEquals(List.of("B D19"), SolutionPathGenerator.answered(List.of(), List.of("B E19", "B D19"), deeper));
    }

    @Test
    void aMoveThatWorksOnlyThroughWhatTheProblemRefutesIsNotSuggested() {
        assertNull(MissingVariationFinder.onlyThroughRefuted(List.of()));
        // another move works beside the one the problem answers: the lines take that one
        assertNull(MissingVariationFinder.onlyThroughRefuted(List.of(
                new SolutionPathGenerator.Refuted(List.of("B T17", "W T18"), List.of("B S18"), false))));
        // gp-60316: after B T17, W T18 only S18 works, which the problem answers at T15
        String why = MissingVariationFinder.onlyThroughRefuted(List.of(
                new SolutionPathGenerator.Refuted(List.of("B T17", "W T18"), List.of("B S18"), true)));
        assertTrue(why.startsWith("works after B T17 W T18 only through S18, which the problem answers itself"), why);
    }

    @Test
    void whatIsAtStakeUnderKosComesFromTheAuthorsOwnMoves() {
        // gp-61269: katago's S16, T17 and T18 are not the author's, and S16 wins a ko four times
        // over for six stones the problem is not about
        List<String> top = List.of("B R16", "B S16", "B T17", "B T18");
        List<String> line = List.of("B R16", "W R15", "B S16", "W S15", "B T15");
        assertEquals(List.of(List.of("B R16"), line),
                List.copyOf(ProblemIsolator.koStarts(top, List.of(line), "B")));
        // an author's first move katago does not list is still played out on its own, after
        // the ones it does list
        List<String> other = List.of("B Q19", "W P19", "B T19");
        assertEquals(List.of(List.of("B R16"), List.of("B Q19"), line, other),
                List.copyOf(ProblemIsolator.koStarts(top, List.of(line, other), "B")));
        // with no line of the author's, katago's first moves stand in
        assertEquals(List.of(List.of("B R16"), List.of("B S16"), List.of("B T17"), List.of("B T18")),
                List.copyOf(ProblemIsolator.koStarts(top, List.of(), "B")));
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
