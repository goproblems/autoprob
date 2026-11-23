package autoprob.validate;

import java.util.ArrayList;
import java.util.List;

// everything a redo run learned about one problem, written as <problem>.json in the
// run directory: the frozen isolation, every decision with its candidates, the
// generated tree, the reference lines and the scores. the review tool and case
// seeding read these.
public class ProblemRecord {
    public String problem; // file name without .sgf
    public String file;    // path to the problem sgf as given to the run
    public String sha;     // sha-1 of the problem sgf, to notice edits
    public String difficulty; // the problem's rank from its DI root property, e.g. "3 dan"
    public String error;
    public IsolationRecord isolation;
    public List<DecisionRecord> decisions = new ArrayList<>();
    public List<String> generated = new ArrayList<>(); // the main line
    public List<List<String>> generatedLines = new ArrayList<>(); // every line of the generated tree
    public List<List<String>> refs = new ArrayList<>(); // the author's RIGHT lines
    public Metrics metrics;
    public TreeComparison tree;
    public List<String> notes = new ArrayList<>();

    public static class Metrics {
        public boolean firstMoveMatch;
        public int prefix;
        public int genLen;
        public int refLen;
        public int delta;
        public boolean exact;
        public boolean endedNaturally;
        public int inTreeDepth;
        public boolean endsOnRight;
    }

    public static List<String> moves(MovePath path) {
        List<String> out = new ArrayList<>();
        for (MovePath.Move m : path.moves) {
            out.add(m.toString());
        }
        return out;
    }
}
