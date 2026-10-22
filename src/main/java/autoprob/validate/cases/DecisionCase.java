package autoprob.validate.cases;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import autoprob.validate.DecisionRecord;
import autoprob.validate.IsolationRecord;

// a test case for one decision: a position in an isolated problem and what the
// generator should do there. the isolation is frozen so a replay tests the decision
// logic alone, and observed keeps the candidates the generator saw when the case was
// recorded, for analysis and offline tuning.
public class DecisionCase {
    public static final String AUTHOR = "author";
    public static final String REVIEW = "review";

    public List<String> at = new ArrayList<>(); // moves from the problem root
    public String toMove;
    public String role;   // solver or opponent
    public Expect expect = new Expect();
    public String source; // author (from the problem's RIGHT paths) or review (judged by a person)
    public List<String> tags = new ArrayList<>(); // about the position as a whole
    public String note;
    // a reviewer's tags and note on single moves from this position, keyed by move (or END)
    public Map<String, MoveNote> moves;
    public String by;
    public String created;
    public IsolationRecord isolation;
    public Observed observed;

    // moves (or END) that are right here, and moves that are wrong. see passes().
    public static class Expect {
        public List<String> choose = new ArrayList<>();
        public List<String> avoid = new ArrayList<>();
    }

    public static class MoveNote {
        public List<String> tags = new ArrayList<>();
        public String note;
    }

    // what the generator saw and picked when the case was recorded
    public static class Observed {
        public String run;
        public String commit;
        public String model;
        public DecisionRecord decision;
    }

    public String key() {
        return DecisionRecord.key(at);
    }

    // every tag on the case, on the position and on its moves
    public List<String> allTags() {
        Set<String> out = new LinkedHashSet<>(tags);
        if (moves != null) {
            for (MoveNote n : moves.values()) {
                if (n != null && n.tags != null) out.addAll(n.tags);
            }
        }
        return new ArrayList<>(out);
    }

    // whether a decision meets this case. no move the decision puts in the tree may be in
    // avoid. at a solver turn every move in choose must be in the tree: each one works,
    // and a working move left out marks a correct answer wrong. at an opponent turn
    // choose lists acceptable resistances, and one of them must be chosen.
    public boolean passes(DecisionRecord d) {
        List<String> moves = d.chosenMoves();
        for (String m : moves) {
            if (expect.avoid.contains(m)) return false;
        }
        if (DecisionRecord.SOLVER.equals(role)) return moves.containsAll(expect.choose);
        if (expect.choose.isEmpty()) return true;
        for (String m : moves) {
            if (expect.choose.contains(m)) return true;
        }
        return false;
    }

    public String describeExpect() {
        StringBuilder sb = new StringBuilder();
        if (!expect.choose.isEmpty()) sb.append("choose ").append(expect.choose);
        if (!expect.avoid.isEmpty()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("avoid ").append(expect.avoid);
        }
        return sb.toString();
    }
}
