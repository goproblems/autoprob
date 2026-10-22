package autoprob.validate.cases;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;
import autoprob.go.parse.Parser;
import autoprob.validate.DecisionRecord;
import autoprob.validate.IsolationRecord;
import autoprob.validate.ProblemRecord;
import autoprob.validate.RunRecorder;

// seeds the case library from the authors' RIGHT paths in a redo run's problems:
// every position along a RIGHT path becomes a case expecting the author's move there,
// and the end of each RIGHT path a case expecting END. these are a free starting set
// of good examples, kept apart from reviewed cases by source=author. positions that
// already have a case are left alone. no engine needed: isolation comes from the run.
public class CaseSeeder {
    private final Properties props;

    public CaseSeeder(Properties props) {
        this.props = props;
    }

    public void run() throws Exception {
        File runDir = RunRecorder.resolveRun(props);
        RunRecorder.Manifest manifest = RunRecorder.readManifest(runDir);
        CaseStore store = new CaseStore(props);
        System.out.println("seeding author cases from run " + manifest.id + " into " + store.getDir());
        int added = 0, files = 0;
        for (String problem : manifest.problems) {
            ProblemRecord rec = RunRecorder.readProblem(runDir, problem);
            if (rec.isolation == null) {
                System.out.println(problem + ": no isolation in run (error: " + rec.error + "), skipping");
                continue;
            }
            int n = seed(store, rec, manifest);
            System.out.println(problem + ": " + n + " new author cases");
            added += n;
            files++;
        }
        System.out.println("added " + added + " cases across " + files + " problems");
    }

    private int seed(CaseStore store, ProblemRecord rec, RunRecorder.Manifest manifest) throws Exception {
        String sgf = Files.readString(new File(rec.file).toPath());
        Node root = new Parser().parse(sgf);

        // expected moves at every position on a RIGHT path, in tree order
        Map<String, TreeSet<String>> expected = new LinkedHashMap<>();
        Map<String, List<String>> positions = new LinkedHashMap<>();
        collect(root, new ArrayList<>(), expected, positions);

        CaseFile cf = store.loadOrCreate(rec.problem);
        if (cf.problemFile == null) {
            cf.problemFile = rec.file;
            cf.problemSha = rec.sha;
        } else if (cf.problemSha != null && !cf.problemSha.equals(rec.sha)) {
            System.out.println("WARNING: " + rec.file + " changed since its cases were recorded");
        }

        int added = 0;
        for (Map.Entry<String, TreeSet<String>> e : expected.entrySet()) {
            if (cf.find(e.getKey()) != null) continue;
            List<String> at = positions.get(e.getKey());
            DecisionCase c = new DecisionCase();
            c.at = at;
            c.toMove = toMove(at, rec.isolation.solver);
            c.role = c.toMove.equals(rec.isolation.solver) ? DecisionRecord.SOLVER : DecisionRecord.OPPONENT;
            c.expect.choose.addAll(e.getValue());
            c.source = DecisionCase.AUTHOR;
            c.by = DecisionCase.AUTHOR;
            c.created = LocalDate.now().toString();
            c.isolation = rec.isolation;
            // if the generator reached this position in the run, keep what it saw
            for (DecisionRecord d : rec.decisions) {
                if (d.key().equals(e.getKey())) {
                    c.observed = new DecisionCase.Observed();
                    c.observed.run = manifest.id;
                    c.observed.commit = manifest.commit;
                    c.observed.model = manifest.model;
                    c.observed.decision = d;
                }
            }
            cf.decisions.add(c);
            added++;
        }
        if (added > 0) store.save(cf);
        return added;
    }

    // walk every branch that leads to a RIGHT node, recording the moves played from each
    // position and END where a RIGHT path stops
    private boolean collect(Node node, List<String> at, Map<String, TreeSet<String>> expected,
                            Map<String, List<String>> positions) {
        boolean right = node.result == Intersection.RIGHT;
        String key = DecisionRecord.key(at);
        for (Node child : node.babies) {
            MoveAction ma = child.getMoveAction();
            if (ma == null || ma.loc.x < 0 || ma.loc.x >= 19 || ma.loc.y < 0 || ma.loc.y >= 19) continue; // no passes
            List<String> next = new ArrayList<>(at);
            next.add(IsolationRecord.colorLetter(ma.stone) + " " + Intersection.toGTPloc(ma.loc.x, ma.loc.y, 19));
            if (collect(child, next, expected, positions)) {
                expected.computeIfAbsent(key, k -> new TreeSet<>()).add(next.get(next.size() - 1));
                positions.put(key, at);
                right = true;
            }
        }
        if (node.result == Intersection.RIGHT && !at.isEmpty()) {
            expected.computeIfAbsent(key, k -> new TreeSet<>()).add(DecisionRecord.END);
            positions.put(key, at);
        }
        return right;
    }

    private static String toMove(List<String> at, String solver) {
        if (at.isEmpty()) return solver;
        return at.get(at.size() - 1).startsWith("B") ? "W" : "B";
    }
}
