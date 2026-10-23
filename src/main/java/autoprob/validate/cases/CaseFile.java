package autoprob.validate.cases;

import java.util.ArrayList;
import java.util.List;

import autoprob.validate.IsolationRecord;

// all cases for one problem, stored as cases/<problem>.json
public class CaseFile {
    public String problem;
    public String problemFile; // the problem sgf, needed for line cases
    public String problemSha;  // sha-1 of the sgf when the first case was recorded
    // the frozen isolation the cases share, stored once. a case recorded on a run that
    // isolated the problem differently keeps its own. CaseStore fills this into every
    // case that has none when it loads the file
    public IsolationRecord isolation;
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
