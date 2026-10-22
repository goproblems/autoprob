package autoprob.validate.cases;

import java.io.File;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import autoprob.KataBrain;
import autoprob.go.Node;
import autoprob.validate.ChoiceParams;
import autoprob.validate.ChoiceRule;
import autoprob.validate.DecisionFeatureExtractor;
import autoprob.validate.DecisionRecord;
import autoprob.validate.MovePath;
import autoprob.validate.Positions;
import autoprob.validate.ProblemIsolator;
import autoprob.validate.ProblemRecord;
import autoprob.validate.ProblemRedoer;
import autoprob.validate.RunRecorder;

// cmd=cases: replays the case library against the current code and settings.
//
// decision cases rebuild their frozen position and ask the generator for just that one
// decision, so a failure points at the decision logic. close calls (choices that flip
// under small nudges, see ChoiceRule.isFragile) are re-run to tell a real failure from
// engine noise. with cases.lines=true, line cases regenerate each problem from scratch
// and check the line against the author's RIGHT paths plus accepted lines.
//
// results go to cases_results/<id>.json, with fresh candidate numbers for every
// decision, and are compared against the previous results file.
public class CaseReplayer {
    public static final String PASS = "pass", FAIL = "fail", UNSTABLE = "unstable", ERROR = "error";

    public static class Results {
        public String id;
        public String started;
        public String finished;
        public String commit;
        public String model;
        public TreeMap<String, String> settings = new TreeMap<>();
        public List<CaseResult> decisions = new ArrayList<>();
        public List<LineResult> lines = new ArrayList<>();
    }

    public static class CaseResult {
        public String problem;
        public List<String> at;
        public String role;
        public String source;
        public List<String> tags;
        public String expect;
        public String outcome;
        public List<String> choices = new ArrayList<>(); // one per attempt
        public boolean fragile;
        public DecisionRecord decision; // fresh numbers from the first attempt
        public String error;

        public String id() {
            return problem + " @ " + DecisionRecord.key(at);
        }
    }

    public static class LineResult {
        public String problem;
        public String outcome;
        public List<String> generated;
        public List<String> closest; // the acceptable line sharing the longest prefix
        public int prefix;
        public String divergence; // the position where generation left the closest line
        public String divergenceCase; // what a decision case there expects, if one exists
        public String error;
    }

    private final Properties props;
    private final ChoiceParams params;

    public CaseReplayer(Properties props) {
        this.props = props;
        this.params = ChoiceParams.fromProps(props);
    }

    public void run() throws Exception {
        CaseStore store = new CaseStore(props);
        List<CaseFile> files = store.loadAll(props.getProperty("problem"));
        String sourceFilter = props.getProperty("source");
        int repeat = Integer.parseInt(props.getProperty("cases.repeat_close", "3"));
        boolean lines = Boolean.parseBoolean(props.getProperty("cases.lines", "false"));
        int total = 0;
        for (CaseFile cf : files) total += cf.decisions.size();
        if (total == 0 && !lines) {
            System.out.println("no cases found in " + store.getDir());
            return;
        }

        Results results = new Results();
        results.id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        results.started = LocalDateTime.now().toString();
        results.commit = RunRecorder.gitCommit();
        results.model = props.getProperty("kata.model");
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("redo.")) results.settings.put(name, props.getProperty(name));
        }

        int visits = Integer.parseInt(props.getProperty("redo.visits", "1000"));
        int visitsRoot = Integer.parseInt(props.getProperty("redo.visits_root", "2000"));
        KataBrain brain = new KataBrain(props);
        try {
            for (CaseFile cf : files) {
                for (DecisionCase c : cf.decisions) {
                    if (sourceFilter != null && !sourceFilter.equals(c.source)) continue;
                    if (!brain.isAlive()) {
                        System.out.println("katago died, restarting engine");
                        brain.stopKataBrain();
                        brain = new KataBrain(props);
                    }
                    CaseResult r = replay(brain, cf, c, visits, visitsRoot, repeat);
                    results.decisions.add(r);
                    System.out.println(r.outcome.toUpperCase() + " " + r.id() + " (" + r.role + "): chose "
                            + String.join(", ", r.choices) + "; expected " + r.expect
                            + (r.error != null ? " [" + r.error + "]" : ""));
                }
            }
            if (lines) {
                for (CaseFile cf : files) {
                    results.lines.add(replayLine(brain, cf));
                }
            }
        } finally {
            brain.stopKataBrain();
        }
        results.finished = LocalDateTime.now().toString();

        File resultsDir = new File(props.getProperty("cases.results.dir", "cases_results"));
        Results previous = latest(resultsDir);
        if (!resultsDir.exists() && !resultsDir.mkdirs()) {
            throw new RuntimeException("cannot create results directory: " + resultsDir);
        }
        File out = new File(resultsDir, results.id + ".json");
        Files.writeString(out.toPath(), RunRecorder.GSON.toJson(results));
        report(results, previous);
        System.out.println("results written to " + out.getPath());
    }

    private CaseResult replay(KataBrain brain, CaseFile cf, DecisionCase c, int visits, int visitsRoot, int repeat) {
        CaseResult r = new CaseResult();
        r.problem = cf.problem;
        r.at = c.at;
        r.role = c.role;
        r.source = c.source;
        r.tags = c.tags;
        r.expect = c.describeExpect();
        try {
            ProblemIsolator.IsolatedProblem iso = c.isolation.toIsolated();
            Node node = Positions.play(iso, c.at);
            DecisionFeatureExtractor extractor = new DecisionFeatureExtractor(props, brain, iso, params);
            int attempts = 1;
            for (int i = 0; i < attempts; i++) {
                DecisionRecord rec;
                ChoiceRule.Choice choice;
                if (c.role.equals(DecisionRecord.SOLVER)) {
                    rec = extractor.solver(node, c.at, c.at.isEmpty() ? visitsRoot : visits);
                    choice = rec.error == null ? ChoiceRule.chooseSolver(rec) : null;
                } else {
                    rec = extractor.opponent(node, c.at);
                    choice = rec.error == null ? ChoiceRule.chooseOpponent(rec, params, iso.stakes) : null;
                }
                if (choice == null) {
                    r.outcome = ERROR;
                    r.error = rec.error;
                    return r;
                }
                rec.chose = choice.move;
                rec.reason = choice.reason;
                r.choices.add(choice.move);
                if (i == 0) {
                    r.decision = rec;
                    r.fragile = ChoiceRule.isFragile(rec, params, iso.stakes);
                    if (r.fragile) attempts = Math.max(1, repeat);
                }
            }
        } catch (Exception e) {
            r.outcome = ERROR;
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
            return r;
        }
        int passed = 0;
        for (String chose : r.choices) {
            if (c.passes(chose)) passed++;
        }
        r.outcome = passed == r.choices.size() ? PASS : passed == 0 ? FAIL : UNSTABLE;
        return r;
    }

    private LineResult replayLine(KataBrain brain, CaseFile cf) {
        LineResult lr = new LineResult();
        lr.problem = cf.problem;
        if (cf.problemFile == null || !new File(cf.problemFile).exists()) {
            lr.outcome = ERROR;
            lr.error = "problem file not found: " + cf.problemFile;
            return lr;
        }
        Properties quiet = new Properties();
        quiet.putAll(props);
        quiet.setProperty("redo.write_file", "false");
        quiet.setProperty("redo.output.isolated", "false");
        ProblemRedoer.RedoResult result = new ProblemRedoer(quiet).redo(brain, new File(cf.problemFile));
        if (result.error != null) {
            lr.outcome = ERROR;
            lr.error = result.error;
            return lr;
        }
        lr.generated = ProblemRecord.moves(result.generated);
        List<List<String>> acceptable = new ArrayList<>(cf.acceptedLines);
        for (MovePath ref : result.refs) acceptable.add(ProblemRecord.moves(ref));
        lr.prefix = -1;
        for (List<String> line : acceptable) {
            int prefix = 0;
            while (prefix < line.size() && prefix < lr.generated.size()
                    && line.get(prefix).equals(lr.generated.get(prefix))) prefix++;
            if (prefix > lr.prefix) {
                lr.prefix = prefix;
                lr.closest = line;
            }
        }
        if (acceptable.contains(lr.generated)) {
            lr.outcome = PASS;
        } else {
            lr.outcome = FAIL;
            List<String> at = lr.generated.subList(0, Math.min(lr.prefix, lr.generated.size()));
            lr.divergence = DecisionRecord.key(at);
            DecisionCase dc = cf.find(lr.divergence);
            if (dc != null) lr.divergenceCase = dc.describeExpect() + " (" + dc.source + ")";
        }
        System.out.println(lr.outcome.toUpperCase() + " line " + cf.problem + ": " + String.join(", ", lr.generated)
                + (lr.divergence != null ? "; left the closest acceptable line at " + lr.divergence : ""));
        return lr;
    }

    private void report(Results results, Results previous) {
        System.out.println();
        System.out.println("==== CASE REPLAY (" + results.decisions.size() + " decision cases) ====");
        Map<String, int[]> bySource = new LinkedHashMap<>();
        Map<String, int[]> byTag = new TreeMap<>();
        for (CaseResult r : results.decisions) {
            count(bySource, r.source, r.outcome);
            for (String tag : r.tags) count(byTag, tag, r.outcome);
        }
        for (Map.Entry<String, int[]> e : bySource.entrySet()) {
            System.out.println(pad(e.getKey(), 10) + tally(e.getValue()));
        }
        if (!byTag.isEmpty()) {
            System.out.println("by tag:");
            for (Map.Entry<String, int[]> e : byTag.entrySet()) {
                System.out.println("  " + pad(e.getKey(), 20) + tally(e.getValue()));
            }
        }
        if (!results.lines.isEmpty()) {
            int pass = 0;
            for (LineResult lr : results.lines) if (lr.outcome.equals(PASS)) pass++;
            System.out.println("lines: " + pass + "/" + results.lines.size() + " reproduce an acceptable line");
        }

        if (previous == null) return;
        Map<String, String> before = new HashMap<>();
        for (CaseResult r : previous.decisions) before.put(r.id(), r.outcome);
        List<String> fixed = new ArrayList<>(), broken = new ArrayList<>(), changed = new ArrayList<>();
        for (CaseResult r : results.decisions) {
            String old = before.get(r.id());
            if (old == null || old.equals(r.outcome)) continue;
            String line = r.id() + ": " + old + " -> " + r.outcome;
            if (r.outcome.equals(PASS)) fixed.add(line);
            else if (old.equals(PASS)) broken.add(line);
            else changed.add(line);
        }
        System.out.println();
        System.out.println("since " + previous.id + ": " + fixed.size() + " fixed, " + broken.size()
                + " broken, " + changed.size() + " other changes");
        for (String s : broken) System.out.println("  broken  " + s);
        for (String s : fixed) System.out.println("  fixed   " + s);
        for (String s : changed) System.out.println("  changed " + s);
    }

    private static void count(Map<String, int[]> map, String key, String outcome) {
        int[] t = map.computeIfAbsent(key == null ? "?" : key, k -> new int[4]);
        switch (outcome) {
            case PASS -> t[0]++;
            case FAIL -> t[1]++;
            case UNSTABLE -> t[2]++;
            default -> t[3]++;
        }
    }

    private static String tally(int[] t) {
        return "pass " + t[0] + ", fail " + t[1] + ", unstable " + t[2] + ", error " + t[3];
    }

    private static String pad(String s, int len) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
    }

    // the most recent results file, or null
    public static Results latest(File dir) throws Exception {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) return null;
        File latest = files[0];
        for (File f : files) {
            if (f.getName().compareTo(latest.getName()) > 0) latest = f;
        }
        return read(latest);
    }

    public static Results read(File f) throws Exception {
        return RunRecorder.GSON.fromJson(Files.readString(f.toPath()), Results.class);
    }
}
