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

    // opponent candidates only
    public Double humanPolicy;  // blended human policy for this move at the decision position
    public String answer;       // the solver's best answer after this move
    public Double answerPrior;  // katago prior of that answer: how obvious it is to the engine
    public Double humanAnswer;  // human policy of that answer: how obvious it is to a human
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
