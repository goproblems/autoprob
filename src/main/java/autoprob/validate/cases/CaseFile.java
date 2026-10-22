package autoprob.validate.cases;

import java.util.ArrayList;
import java.util.List;

// all cases for one problem, stored as cases/<problem>.json
public class CaseFile {
    public String problem;
    public String problemFile; // the problem sgf, needed for line cases
    public String problemSha;  // sha-1 of the sgf when the first case was recorded
    // whole lines judged acceptable beyond the author's RIGHT paths. a line case passes
    // when a fresh generation reproduces one of the author's paths or one of these
    public List<List<String>> acceptedLines = new ArrayList<>();
    public List<DecisionCase> decisions = new ArrayList<>();

    public DecisionCase find(String key) {
        for (DecisionCase c : decisions) {
            if (c.key().equals(key)) return c;
        }
        return null;
    }
}
