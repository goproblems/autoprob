package autoprob.validate;

import java.util.LinkedHashMap;
import java.util.Map;

// the numbers behind one candidate move in a decision. these come from the engine
// (the expensive part) and are everything ChoiceRule needs to pick a move, so a
// decision can be re-chosen offline under different settings.
// optional fields are left null where they do not apply (gson omits nulls).
public class CandidateFeatures {
    public String move;   // with color, e.g. "W F1"
    public String source; // katago or human: how the candidate was found

    // katago's view of the move at the decision position
    public Double prior;
    public Integer visits;
    public Double scoreLead; // solver candidates only

    // solver candidates only, when alternatives are evaluated: the position after the
    // move analyzed on its own, so every plausible move gets a reliable verdict
    public Double afterLead; // the solver's score lead after this move (solver's perspective)

    public Double humanPolicy;  // blended human policy for this move at the decision position

    // opponent candidates only
    public String answer;       // the solver's best answer after this move
    public Double answerPrior;  // katago prior of that answer: how obvious it is to the engine
    public Double humanAnswer;  // human policy of that answer: how obvious it is to a human
    // human policy summed over every solver answer that holds (those answers counts), up
    // to 1: how likely a human is to find some correct answer. a response several moves
    // answer is easier than its best answer's policy suggests. older records lack it
    public Double humanAnswers;
    // solver answers that hold the result after this move: katago moves its search took
    // seriously (a tenth of the top move's visits) scoring within alt_score_tolerance of the
    // best. one answer means the move tests the solver; many mean anything answers it
    public Integer answers;
    public Integer stakeDist;   // distance to the nearest stake stone
    public Integer threat;      // stones changing fate if ignored, at the recording threshold
    // ownership change per real stone between answering and ignoring this move
    // (answered minus ignored). threat recounts these, so threshold changes can be
    // scored offline.
    public Map<String, Double> deltas;
    public Boolean error;       // the threat analysis failed, threat counts as 0

    public CandidateFeatures() {
    }

    public CandidateFeatures(String move, String source) {
        this.move = move;
        this.source = source;
    }

    public void putDelta(String gtp, double delta) {
        if (deltas == null) deltas = new LinkedHashMap<>();
        deltas.put(gtp, Math.round(delta * 1000.0) / 1000.0);
    }

    // just the point, e.g. "F1"
    public String point() {
        return move.substring(2);
    }
}
