package autoprob.validate.cases;

import java.util.ArrayList;
import java.util.List;

import autoprob.validate.DecisionRecord;
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

    // a case below a position where the line must end (END alone right there): the tree
    // should never get there, so the case says nothing about the generator. such cases are
    // kept but not counted. "either way" above does not make a case moot
    public boolean moot(DecisionCase c) {
        for (int i = 1; i < c.at.size(); i++) {
            DecisionCase above = find(DecisionRecord.key(c.at.subList(0, i)));
            if (above != null && above.expect.choose.size() == 1 && above.expect.choose.contains(DecisionRecord.END)) {
                return true;
            }
        }
        return false;
    }
}
