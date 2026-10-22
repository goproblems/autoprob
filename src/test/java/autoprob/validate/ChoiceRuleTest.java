package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
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
        assertEquals("W N1", ChoiceRule.chooseOpponent(gp721(), params(), STAKES).move);
    }

    @Test
    void humanPicksTheMostNaturalThreat() {
        assertEquals("W F1", ChoiceRule.chooseOpponent(gp721(), params("response_criterion", "human"), STAKES).move);
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
        assertTrue(ChoiceRule.isFragile(rec, params(), STAKES));
        DecisionRecord clear = opponent(cand("W F1", "katago", 0.6, 500, 0.6, 0.9, 12, 0),
                cand("W J1", "katago", 0.2, 200, 0.1, 0.1, 12, 0));
        assertFalse(ChoiceRule.isFragile(clear, params(), STAKES));
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
    void solverMovesAreCapped() {
        DecisionRecord rec = solver(solverCand("B A1", "katago", 2.0), solverCand("B B1", "katago", 2.0),
                solverCand("B C1", "katago", 2.0), solverCand("B D1", "katago", 2.0), solverCand("B E1", "human", 2.0));
        assertEquals(4, ChoiceRule.chooseSolver(rec, params()).moves().size());
        assertEquals(2, ChoiceRule.chooseSolver(rec, params("max_solver_moves", "2")).moves().size());
    }

    @Test
    void withoutEvaluatedAlternativesTheTopMoveStandsAlone() {
        DecisionRecord rec = solver(solverCand("B L1", "katago", null), solverCand("B J1", "katago", null));
        ChoiceRule.Choice choice = ChoiceRule.chooseSolver(rec, params());
        assertEquals("B L1", choice.move);
        assertTrue(choice.also.isEmpty());
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
        assertEquals("W F1", ChoiceRule.chooseOpponent(rec, params(), STAKES).move);
        assertEquals("W N1", ChoiceRule.chooseOpponent(rec, params("max_answers", "0"), STAKES).move);
        // when every threat is answered by anything, the line ends
        rec.candidate("W F1").answers = 4;
        ChoiceRule.Choice choice = ChoiceRule.chooseOpponent(rec, params(), STAKES);
        assertTrue(choice.isEnd());
        assertTrue(choice.reason.startsWith("no testing threat remains"));
    }

    @Test
    void extraOpponentBranchesFollowTheCriterion() {
        ChoiceRule.Choice choice = ChoiceRule.chooseOpponent(gp721(), params("opponent_branches", "2"), STAKES);
        assertEquals("W N1", choice.move);
        assertEquals(List.of("W F1"), choice.also); // J1 threatens too little to count
        // order takes threatening moves in katago's order, skipping J1
        assertEquals(List.of("W F1", "W N1"), ChoiceRule.chooseOpponent(gp721(),
                params("opponent_branches", "2", "response_criterion", "order"), STAKES).moves());
    }
}
