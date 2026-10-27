package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.Test;

import autoprob.go.Intersection;

class ChoiceRuleTest {
    private static final List<Point> STAKES = List.of(Intersection.gtp2point("H2"), Intersection.gtp2point("J2"));

    // a candidate whose threat is `threat` stones near the stakes, plus `far` distant stones
    private static CandidateFeatures cand(String move, String source, double prior, int visits, double human,
                                          double humanAnswer, int threat, int far) {
        CandidateFeatures c = new CandidateFeatures(move, source);
        c.prior = prior;
        c.visits = visits;
        c.humanPolicy = human;
        c.humanAnswer = humanAnswer;
        c.answerPrior = 0.5;
        c.stakeDist = 1;
        String[] near = {"G1", "G2", "G3", "H1", "H2", "H3", "J1", "J2", "J3", "K1", "K2", "K3", "F1", "F2", "F3", "L1", "L2"};
        for (int i = 0; i < threat - far; i++) c.putDelta(near[i], 1.9);
        String[] distant = {"A19", "B19", "C19", "D19", "E19", "F19"};
        for (int i = 0; i < far; i++) c.putDelta(distant[i], 1.5);
        c.putDelta("T1", 0.4); // below any threshold
        c.threat = threat;
        return c;
    }

    private static DecisionRecord opponent(CandidateFeatures... cands) {
        DecisionRecord rec = new DecisionRecord();
        rec.toMove = "W";
        rec.role = DecisionRecord.OPPONENT;
        rec.candidates.addAll(List.of(cands));
        return rec;
    }

    private static ChoiceParams params(String... settings) {
        ChoiceParams p = ChoiceParams.fromProps(new Properties());
        for (int i = 0; i < settings.length; i += 2) p.set(settings[i], settings[i + 1]);
        return p;
    }

    // gp-721 after B L1: F1 is the natural resistance, N1 the one whose answer is hard to find
    private static DecisionRecord gp721() {
        return opponent(
                cand("W F1", "katago", 0.90, 810, 0.83, 0.33, 12, 0),
                cand("W J1", "katago", 0.03, 121, 0.09, 0.06, 3, 0),
                cand("W N1", "katago", 0.02, 13, 0.09, 0.11, 14, 0));
    }

    @Test
    void testingPicksTheResistanceWhoseAnswerIsHardestForAHuman() {
        assertEquals("W N1", ChoiceRule.chooseOpponent(gp721(), params("naturalness_weight", "0"), STAKES).move);
    }

    // gp-8142 after W P18: M18 is a move no human would play whose best answer is obscure,
    // P17 the natural resistance (and the author's)
    private static DecisionRecord gp8142() {
        return opponent(
                cand("B M18", "katago", 0.025, 80, 0.0, 0.337, 16, 0),
                cand("B P17", "human", 0.0, 0, 0.833, 0.707, 15, 0));
    }

    @Test
    void testingWeighsHowNaturalTheResistanceIs() {
        assertEquals("B P17", ChoiceRule.chooseOpponent(gp8142(), params(), STAKES).move);
        assertEquals("B M18", ChoiceRule.chooseOpponent(gp8142(), params("naturalness_weight", "0"), STAKES).move);
        // likewise gp-721's natural F1 over N1
        assertEquals("W F1", ChoiceRule.chooseOpponent(gp721(), params(), STAKES).move);
    }

    @Test
    void endsWhenEveryTryLeftHasAnObviousAnswer() {
        // gp-10330 deep in the main line: R2 and S1 both threaten, but katago is sure of each
        // answer and a human finds it
        CandidateFeatures r2 = cand("W R2", "katago", 0.4, 400, 0.08, 0.96, 12, 0);
        CandidateFeatures s1 = cand("W S1", "katago", 0.3, 300, 0.06, 0.95, 12, 0);
        r2.answerPrior = 1.0;
        s1.answerPrior = 1.0;
        ChoiceRule.Choice choice = ChoiceRule.chooseOpponent(opponent(r2, s1), params(), STAKES);
        assertTrue(choice.isEnd());
        assertTrue(choice.reason.startsWith("only routine tries remain"), choice.reason);
        assertEquals("W R2", ChoiceRule.chooseOpponent(opponent(r2, s1), params("end_on_obvious", "false"), STAKES).move);
        // a single last try is still shown, however obvious its answer
        assertEquals("W R2", ChoiceRule.chooseOpponent(opponent(r2), params(), STAKES).move);
        assertTrue(ChoiceRule.chooseOpponent(opponent(r2), params("routine_min_tries", "1"), STAKES).isEnd());
        // one try whose answer a human misses keeps the line going
        s1.humanAnswer = 0.4;
        assertEquals("W S1", ChoiceRule.chooseOpponent(opponent(r2, s1), params(), STAKES).move);
    }

    @Test
    void anyAnswerThatHoldsCountsTowardTheAnswerChance() {
        // after M18 four answers hold: together a human finds one about as often as Q18
        // after P17, so the two tie and naturalness decides even without its weight
        DecisionRecord rec = gp8142();
        rec.candidate("B M18").humanAnswers = 0.68;
        rec.candidate("B P17").humanAnswers = 0.707;
        assertEquals("B P17", ChoiceRule.chooseOpponent(rec, params("naturalness_weight", "0"), STAKES).move);
    }

    @Test
    void humanPicksTheMostNaturalThreat() {
        assertEquals("W F1", ChoiceRule.chooseOpponent(gp721(), params("response_criterion", "human"), STAKES).move);
    }

    @Test
    void orderEndsWhenTheTriesLeftAreRoutine() {
        // gp-62070 after B R19, W R16, B S19 (the review: the line ends there). P17 and T19
        // threaten, but black's answers are found every time; P16 has seven answers, so it
        // tests nothing, and T13 threatens nothing
        CandidateFeatures p16 = cand("W P16", "human", 0.0, 0, 0.351, 0.051, 14, 0);
        CandidateFeatures p17 = cand("W P17", "human", 0.0, 0, 0.127, 0.993, 16, 0);
        CandidateFeatures t19 = cand("W T19", "human", 0.0, 0, 0.089, 0.897, 14, 0);
        CandidateFeatures t13 = cand("W T13", "human", 0.0, 0, 0.055, 0.006, 0, 0);
        p16.answers = 7;
        p17.answers = 1;
        p17.answerPrior = 1.0;
        t19.answers = 2;
        t19.answerPrior = 0.837;
        t19.humanAnswers = 1.0;
        DecisionRecord rec = opponent(p16, p17, t19, t13);
        ChoiceRule.Choice choice = ChoiceRule.chooseOpponent(rec, params("response_criterion", "order"), STAKES);
        assertTrue(choice.isEnd(), choice.reason);
        assertTrue(choice.reason.startsWith("only routine tries remain"), choice.reason);
        // without the routine rule, katago's first real threat goes on
        assertEquals("W P17", ChoiceRule.chooseOpponent(rec, params("response_criterion", "order", "end_on_obvious", "false"), STAKES).move);
    }

    @Test
    void orderAndPrimaryTakeKatagosFirstThreat() {
        assertEquals("W F1", ChoiceRule.chooseOpponent(gp721(), params("response_criterion", "order"), STAKES).move);
        assertEquals("W F1", ChoiceRule.chooseOpponent(gp721(), params("extend_mode", "primary"), STAKES).move);
    }

    @Test
    void endsWhenNothingThreatensEnoughStones() {
        DecisionRecord rec = opponent(cand("W M1", "katago", 0.8, 400, 0.5, 0.9, 3, 0),
                cand("W R3", "human", 0.0, 0, 0.1, 0.7, 0, 0));
        ChoiceRule.Choice choice = ChoiceRule.chooseOpponent(rec, params(), STAKES);
        assertTrue(choice.isEnd());
        assertEquals("W M1", ChoiceRule.chooseOpponent(rec, params("threat_stones", "3"), STAKES).move);
    }

    @Test
    void weakThreatsBelowTheSevereFloorAreNotChosen() {
        // the 5 stone threat has the hardest answer, but is under 0.6 of the 16 stone threat
        DecisionRecord rec = opponent(cand("W F1", "katago", 0.6, 500, 0.6, 0.9, 16, 0),
                cand("W J1", "katago", 0.2, 200, 0.2, 0.05, 5, 0));
        assertEquals("W F1", ChoiceRule.chooseOpponent(rec, params(), STAKES).move);
        assertEquals("W J1", ChoiceRule.chooseOpponent(rec, params("severe_threat_ratio", "0.3"), STAKES).move);
    }

    @Test
    void threatIsRecountedFromStoredDeltas() {
        CandidateFeatures c = cand("W F1", "katago", 0.6, 500, 0.6, 0.9, 6, 2);
        assertEquals(6, ChoiceRule.countThreat(c, 1.1, -1, STAKES));
        assertEquals(4, ChoiceRule.countThreat(c, 1.6, -1, STAKES)); // the 1.5 deltas drop out
        assertEquals(4, ChoiceRule.countThreat(c, 1.1, 2, STAKES));  // the distant stones drop out
        assertEquals(7, ChoiceRule.countThreat(c, 0.3, -1, STAKES)); // the 0.4 delta counts
    }

    @Test
    void gatheringCanNarrowButKeepsEverythingAtRecordingSettings() {
        DecisionRecord rec = gp721();
        assertEquals(3, ChoiceRule.gather(rec, params()).size());
        // one katago candidate, and no human extension: only F1 is left
        List<CandidateFeatures> narrow = ChoiceRule.gather(rec, params("response_candidates", "1", "human_threat_extends", "false"));
        assertEquals(1, narrow.size());
        assertEquals("W F1", narrow.get(0).move);
    }

    @Test
    void choiceNearABandEdgeIsFragile() {
        // F1's answer at 0.39 sits just under the 0.4 band edge, J1's at 0.25: same band would fall
        // through to human policy, so a few hundredths decide it
        DecisionRecord rec = opponent(cand("W F1", "katago", 0.6, 500, 0.6, 0.39, 12, 0),
                cand("W J1", "katago", 0.2, 200, 0.1, 0.25, 12, 0));
        ChoiceParams answersOnly = params("naturalness_weight", "0");
        assertTrue(ChoiceRule.isFragile(rec, answersOnly, STAKES));
        DecisionRecord clear = opponent(cand("W F1", "katago", 0.6, 500, 0.6, 0.9, 12, 0),
                cand("W J1", "katago", 0.2, 200, 0.1, 0.1, 12, 0));
        assertFalse(ChoiceRule.isFragile(clear, answersOnly, STAKES));
    }

    @Test
    void solverPassEndsTheLine() {
        DecisionRecord rec = new DecisionRecord();
        rec.toMove = "B";
        rec.role = DecisionRecord.SOLVER;
        CandidateFeatures pass = new CandidateFeatures("B pass", "katago");
        pass.visits = 900;
        rec.candidates.add(pass);
        assertTrue(ChoiceRule.chooseSolver(rec, params()).isEnd());
        rec.candidates.add(0, new CandidateFeatures("B L1", "katago"));
        assertEquals("B L1", ChoiceRule.chooseSolver(rec, params()).move);
    }

    private static CandidateFeatures solverCand(String move, String source, Double afterLead) {
        CandidateFeatures c = new CandidateFeatures(move, source);
        c.prior = 0.1;
        c.visits = 100;
        c.afterLead = afterLead;
        c.stakeDist = 1;
        return c;
    }

    private static DecisionRecord solver(CandidateFeatures... cands) {
        DecisionRecord rec = new DecisionRecord();
        rec.toMove = "B";
        rec.role = DecisionRecord.SOLVER;
        rec.candidates.addAll(List.of(cands));
        return rec;
    }

    @Test
    void everySolverMoveThatWorksIsKept() {
        DecisionRecord rec = solver(solverCand("B L1", "katago", 2.4), solverCand("B J1", "katago", -12.0),
                solverCand("B K1", "human", 1.8), solverCand("B M1", "katago", 0.5));
        ChoiceRule.Choice choice = ChoiceRule.chooseSolver(rec, params());
        assertEquals("B L1", choice.move);
        assertEquals(List.of("B K1"), choice.also); // M1 is 1.9 behind L1, past the 1.5 tolerance
        assertEquals(List.of("B L1", "B K1", "B M1"),
                ChoiceRule.chooseSolver(rec, params("alt_score_tolerance", "2.0")).moves());
    }

    @Test
    void aWorkingMoveLeadsWhenKatagosTopMoveFails() {
        DecisionRecord rec = solver(solverCand("B J1", "katago", -12.0), solverCand("B L1", "katago", 2.4));
        ChoiceRule.Choice choice = ChoiceRule.chooseSolver(rec, params());
        assertEquals("B L1", choice.move);
        assertTrue(choice.also.isEmpty());
    }

    @Test
    void aBarelySearchedMoveCannotPushOutTheRealAnswer() {
        // gp-721 after B L1 W F1: katago's search put 985 visits on G1 and 2 on R3, but the
        // analysis after R3 wandered into a line that flattered it
        CandidateFeatures g1 = solverCand("B G1", "katago", 0.82);
        g1.visits = 985;
        CandidateFeatures r3 = solverCand("B R3", "katago", 5.99);
        r3.visits = 2;
        r3.stakeDist = 5;
        ChoiceRule.Choice choice = ChoiceRule.chooseSolver(solver(g1, r3), params());
        assertEquals("B G1", choice.move);
        assertTrue(choice.also.isEmpty()); // R3 is far from the stakes: a tenuki, not an answer
        // even near the fight, R3 does not set the bar: it works alongside G1 rather than replacing it
        r3.stakeDist = 2;
        assertEquals(List.of("B G1", "B R3"), ChoiceRule.chooseSolver(solver(g1, r3), params()).moves());
    }

    @Test
    void everyWorkingSolverMoveIsKeptUnlessCapped() {
        DecisionRecord rec = solver(solverCand("B A1", "katago", 2.0), solverCand("B B1", "katago", 2.0),
                solverCand("B C1", "katago", 2.0), solverCand("B D1", "katago", 2.0), solverCand("B E1", "human", 2.0));
        assertEquals(5, ChoiceRule.chooseSolver(rec, params()).moves().size());
        ChoiceRule.Choice capped = ChoiceRule.chooseSolver(rec, params("max_solver_moves", "2"));
        assertEquals(2, capped.moves().size());
        assertEquals("5 moves work, kept 2", capped.reason);
    }

    @Test
    void withoutEvaluatedAlternativesTheTopMoveStandsAlone() {
        DecisionRecord rec = solver(solverCand("B L1", "katago", null), solverCand("B J1", "katago", null));
        ChoiceRule.Choice choice = ChoiceRule.chooseSolver(rec, params());
        assertEquals("B L1", choice.move);
        assertTrue(choice.also.isEmpty());
    }

    // a solver candidate measured with a life status: the stones whose ownership moved, each
    // by delta from the solver's (black's) view, against the best move's. black's stones
    // first, then white's
    private static CandidateFeatures statusCand(String move, String source, double afterLead, double... deltas) {
        CandidateFeatures c = solverCand(move, source, afterLead);
        c.statusDeltas = new LinkedHashMap<>();
        String[] stones = {"B G2", "B H2", "B J2", "W K2", "W G3", "W H3"};
        for (int i = 0; i < deltas.length; i++) c.statusDeltas.put(stones[i], deltas[i]);
        return c;
    }

    @Test
    void aMoveThatReachesTheSameStatusWorksWhateverItCosts() {
        // goproblems grades a standard problem by status: K1 lives as L1 does but 4.5 points
        // worse, J1 loses three of black's stones
        DecisionRecord rec = solver(statusCand("B L1", "katago", 2.4), statusCand("B K1", "scan", -2.1, -0.6),
                statusCand("B J1", "katago", 1.9, -1.8, -1.9, -1.7));
        assertEquals(List.of("B L1", "B K1"), ChoiceRule.chooseSolver(rec, params()).moves());
        // past the status margin, some change no stone shows is likely
        assertEquals(List.of("B L1"), ChoiceRule.chooseSolver(rec, params("status_score_margin", "4")).moves());
    }

    @Test
    void losingAStoneOrTwoIsNotWrongButHalfTheStakesIs() {
        List<Point> six = List.of(new Point(0, 0), new Point(1, 0), new Point(2, 0), new Point(3, 0),
                new Point(4, 0), new Point(5, 0));
        DecisionRecord rec = solver(statusCand("B L1", "katago", 2.4), statusCand("B K1", "katago", 0.4, -1.9, -1.8));
        assertEquals(List.of("B L1", "B K1"), ChoiceRule.chooseSolver(rec, params(), six).moves());
        // two of four stones at stake is half the group
        assertEquals(List.of("B L1"), ChoiceRule.chooseSolver(rec, params(), six.subList(0, 4)).moves());
        assertEquals(List.of("B L1"), ChoiceRule.chooseSolver(rec, params("status_stones", "1"), six).moves());
    }

    @Test
    void anOpponentStoneThatLivesIsADifferentResult() {
        // gp-32711: white's stones live in a seki where the best move kills them. no stone of
        // the opponent's may survive, while one of the solver's may be lost; a change for the
        // better does not count
        DecisionRecord rec = solver(statusCand("B L1", "katago", 2.4),
                statusCand("B K1", "katago", 2.0, 0, 0, 0, -1.9),
                statusCand("B M1", "katago", 2.0, -1.9, 0, 0, 1.9, 1.8));
        assertEquals(List.of("B L1", "B M1"), ChoiceRule.chooseSolver(rec, params()).moves());
    }

    @Test
    void aCleanResultComesAheadOfAKo() {
        // gp-32711 after B O18 W N18 B Q19 W O19: T15 keeps the status on katago's numbers,
        // but its line fights a ko where T16's is clean
        CandidateFeatures t16 = statusCand("B T16", "katago", 0.7);
        CandidateFeatures t15 = statusCand("B T15", "katago", 0.5);
        t15.koFight = "B T18";
        ChoiceRule.Choice choice = ChoiceRule.chooseSolver(solver(t15, t16), params());
        assertEquals(List.of("B T16"), choice.moves()); // the clean move leads, though katago put T15 first
        assertEquals("the only move that works (1 more only through a ko)", choice.reason);
        // when every working move needs a ko, they all count
        t16.koFight = "B T17";
        assertEquals(List.of("B T15", "B T16"), ChoiceRule.chooseSolver(solver(t15, t16), params()).moves());
    }

    @Test
    void aScannedMoveNeverSetsTheBar() {
        // the scan's moves were barely searched at the decision, like a low-visit katago move
        DecisionRecord rec = solver(statusCand("B L1", "katago", 0.5), statusCand("B K1", "scan", 6.0));
        assertEquals(0.5, ChoiceRule.bestAfterLead(rec));
        assertEquals("B L1", ChoiceRule.chooseSolver(rec, params()).move);
    }

    @Test
    void aStoneNearTheFateLineIsFragile() {
        assertTrue(ChoiceRule.isFragile(solver(statusCand("B L1", "katago", 2.4),
                statusCand("B K1", "katago", 2.0, 1.1)), params(), STAKES));
        assertFalse(ChoiceRule.isFragile(solver(statusCand("B L1", "katago", 2.4),
                statusCand("B K1", "katago", 2.0, 0.6)), params(), STAKES));
    }

    @Test
    void solverMoveNearTheToleranceLineIsFragile() {
        // the line is 2.4 - 1.5 = 0.9; K1 at 1.1 is within half a point of it
        assertTrue(ChoiceRule.isFragile(solver(solverCand("B L1", "katago", 2.4),
                solverCand("B K1", "katago", 1.1)), params(), STAKES));
        assertFalse(ChoiceRule.isFragile(solver(solverCand("B L1", "katago", 2.4),
                solverCand("B K1", "katago", -8.0)), params(), STAKES));
    }

    @Test
    void aThreatThatManyMovesAnswerIsNoRealTry() {
        // gp-721 after B L1: after N1 four or more answers hold, after F1 only G1 does
        DecisionRecord rec = gp721();
        rec.candidate("W F1").answers = 1;
        rec.candidate("W J1").answers = 1;
        rec.candidate("W N1").answers = 5;
        assertEquals("W F1", ChoiceRule.chooseOpponent(rec, params("naturalness_weight", "0"), STAKES).move);
        assertEquals("W N1", ChoiceRule.chooseOpponent(rec, params("max_answers", "0", "naturalness_weight", "0"), STAKES).move);
        // when every threat is answered by anything, the line ends
        rec.candidate("W F1").answers = 4;
        ChoiceRule.Choice choice = ChoiceRule.chooseOpponent(rec, params(), STAKES);
        assertTrue(choice.isEnd());
        assertTrue(choice.reason.startsWith("no testing threat remains"));
    }

    @Test
    void extraOpponentBranchesFollowTheCriterion() {
        ChoiceRule.Choice choice = ChoiceRule.chooseOpponent(gp721(), params("opponent_branches", "2"), STAKES);
        assertEquals("W F1", choice.move);
        assertEquals(List.of("W N1"), choice.also); // J1 threatens too little to count
        // order takes threatening moves in katago's order, skipping J1
        assertEquals(List.of("W F1", "W N1"), ChoiceRule.chooseOpponent(gp721(),
                params("opponent_branches", "2", "response_criterion", "order"), STAKES).moves());
    }
}
