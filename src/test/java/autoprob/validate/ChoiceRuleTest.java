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
        assertTrue(ChoiceRule.chooseSolver(rec).isEnd());
        rec.candidates.add(0, new CandidateFeatures("B L1", "katago"));
        assertEquals("B L1", ChoiceRule.chooseSolver(rec).move);
    }
}
