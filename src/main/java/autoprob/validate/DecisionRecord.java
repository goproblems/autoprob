package autoprob.validate;

import java.util.ArrayList;
import java.util.List;

// one decision made while generating a solution line: at a position, the generator
// either picked a move or ended the line. holds the candidates it weighed, so the
// decision can be reviewed, replayed and re-chosen offline.
public class DecisionRecord {
    public static final String END = "END";
    public static final String SOLVER = "solver";
    public static final String OPPONENT = "opponent";

    public List<String> at = new ArrayList<>(); // moves from the problem root, e.g. ["B L1", "W N1"]
    public String toMove; // B or W
    public String role;   // solver or opponent
    public List<CandidateFeatures> candidates = new ArrayList<>();
    public String chose;  // a move with color, or END
    public String reason; // why, in words
    public String error;  // set when the engine analysis failed

    public static String key(List<String> at) {
        return at.isEmpty() ? "root" : String.join(" ", at);
    }

    public String key() {
        return key(at);
    }

    public CandidateFeatures candidate(String move) {
        for (CandidateFeatures c : candidates) {
            if (c.move.equals(move)) return c;
        }
        return null;
    }
}
