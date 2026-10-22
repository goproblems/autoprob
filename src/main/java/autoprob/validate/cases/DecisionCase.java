package autoprob.validate.cases;

import java.util.ArrayList;
import java.util.List;

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
    public List<String> tags = new ArrayList<>();
    public String note;
    public String by;
    public String created;
    public IsolationRecord isolation;
    public Observed observed;

    // moves (or END) that are right here, and moves that are wrong. a decision passes
    // when it avoids every avoid entry and, if choose is not empty, picks one of choose.
    public static class Expect {
        public List<String> choose = new ArrayList<>();
        public List<String> avoid = new ArrayList<>();
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

    public boolean passes(String chose) {
        if (expect.avoid.contains(chose)) return false;
        return expect.choose.isEmpty() || expect.choose.contains(chose);
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
