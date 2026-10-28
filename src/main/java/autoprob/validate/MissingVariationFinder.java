package autoprob.validate;

import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.google.gson.Gson;

import autoprob.ApiClient;
import autoprob.KataBrain;
import autoprob.api.Problem;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.CommentAction;
import autoprob.go.action.MoveAction;
import autoprob.go.parse.Parser;
import autoprob.go.parse.SmallBoard;
import autoprob.katastruct.KataAnalysisResult;

// cmd=missing-variations: checks a problem's correct moves against what the position allows,
// and stops at the first position where they disagree. it walks every position on the
// author's correct lines where the solver is to play, root first. at each, every move worth
// judging gets the level of result it reaches (MoveClassifier): clean, seki, ko or failure,
// the way goproblems ranks them, never by points. the best level any move reaches is the
// answer there, worked out without trusting the author, and the correct moves are the ones
// that reach it. a correct move the problem does not accept is a missing variation; a move
// it accepts that falls short is an error in the problem. both are judged again with a
// deeper search before they are reported, and the problem is written to missing.dir with
// the missing lines added.
//
// sources: path=<sgf file or directory>, id=<goproblems ids, comma separated>, or neither
// to scan the goproblems database by id from where the last scan stopped. problems from
// the site are only checked when they are life and death and marked standard.
public class MissingVariationFinder {
    private static final DecimalFormat df = new DecimalFormat("+0.00;-0.00");
    // what judged a problem, for the crawl to tell an older judging from the current one
    // (crawl.recheck): goes up with every change to how moves are judged
    static final int JUDGING_VERSION = 4;

    private final Properties props;
    private final ChoiceParams params;
    private final File dir;
    private final int visits;
    private final int visitsRoot;
    private final int replyVisits;   // the quick walk after each of the opponent's answers
    private final int verifyVisits;  // the deeper walks, and everything reported is judged again with them
    private final int replyDist;     // answers this close to a stone at stake or the move are tried
    private final boolean skipKo;
    private final boolean allPositions;
    private KataBrain brain;

    // a position on the author's correct lines where the solver is to play, and the moves
    // the author accepts there
    // (the author's lines from there too)
    record Position(List<String> at, Set<String> correct, List<List<String>> lines) {}

    // what judging a problem needs, built once per problem
    record Judging(ProblemIsolator.IsolatedProblem iso, DecisionFeatureExtractor extractor, StatusJudge judge,
                   RegimeWalker walker, MoveClassifier quick, MoveClassifier deep) {}

    // what a scan of the site remembers between runs
    static class State {
        int next = 1;     // the next problem id to try
        int checked;      // problems checked
        int skipped;      // ids passed over: no problem, other genres, not standard, no correct line
        String lastFound; // the last problem a missing variation was found in
        String updated;
    }

    // what checking one problem came to
    static class Outcome {
        String problem;
        String status; // found, clean, skipped or error
        String reason;
        int positions; // positions on the author's lines checked
        int koSkipped; // of those, passed over because the author's line goes through a ko
        long seconds;
        Finding finding;
        // with missing.all_positions, the finding at every position that has one
        transient List<Finding> findings = new ArrayList<>();
    }

    // the first position where the problem disagrees with what the position allows
    static class Finding {
        String problem;
        String difficulty;
        List<String> at;                               // moves from the root
        List<String> accepted = new ArrayList<>();     // the moves the problem accepts there
        MoveClassifier.Level level;                    // the best level any move reaches there
        List<FoundMove> moves = new ArrayList<>();     // correct moves the problem lacks
        List<WrongMove> wrong = new ArrayList<>();     // moves it accepts that fall short
        String sgf;                                    // the problem with the new lines added
        // for the review tool, not the json: the decision at the position, with every
        // candidate measured there, and the isolation it was measured on
        transient DecisionRecord decision;
        transient ProblemIsolator.IsolatedProblem iso;
        transient File review;                         // the review run the finding was added to
    }

    static class FoundMove {
        String move;
        MoveClassifier.Level level;
        String source;          // katago, human or scan: how the move came up
        Double prior;
        Integer visits;
        Double humanPolicy;
        Integer stakeDist;
        boolean inFileAsWrong;  // the problem has the move, on a line that is not correct
        List<String> line;      // the walk that shows the result, ko exchanges taken out
        List<List<String>> lines = new ArrayList<>(); // generated lines from the position, starting with the move
        Confidence confidence;  // how far to trust it, for the review before it reaches the site
        // for the review tool: the generator's decisions behind the lines, at from the root
        transient List<DecisionRecord> decisions = new ArrayList<>();
    }

    // a move the problem accepts that reaches a lower level than the best
    static class WrongMove {
        String move;
        MoveClassifier.Level level;
        String refutation;      // the opponent's answer that brings it down
        List<String> line;      // the walk that shows it, ko exchanges taken out
        Confidence confidence;  // how far to trust it, for the review before it reaches the site
    }

    public MissingVariationFinder(Properties props) {
        this.props = new Properties();
        this.props.putAll(props);
        // every move near the stakes is a candidate, however unlikely katago and the human
        // policy rate it
        this.props.setProperty("redo.solver_alternatives", "true");
        if (!props.containsKey("redo.scan_dist")) this.props.setProperty("redo.scan_dist", "2");
        this.params = ChoiceParams.fromProps(this.props);
        this.dir = new File(props.getProperty("missing.dir", "missing_variations"));
        this.visits = Integer.parseInt(props.getProperty("redo.visits", "1000"));
        this.visitsRoot = Integer.parseInt(props.getProperty("redo.visits_root", "2000"));
        this.replyVisits = Integer.parseInt(props.getProperty("missing.reply_visits", "300"));
        this.verifyVisits = Integer.parseInt(props.getProperty("missing.verify_visits", String.valueOf(2 * visits)));
        this.replyDist = Integer.parseInt(props.getProperty("missing.reply_dist", "2"));
        // with skip_ko, positions whose own line goes through a ko are passed over
        this.skipKo = Boolean.parseBoolean(props.getProperty("missing.skip_ko", "false"));
        // with all_positions (the crawl), a problem's every position is checked rather than
        // up to the first finding, and the caller writes the findings out
        this.allPositions = Boolean.parseBoolean(props.getProperty("missing.all_positions", "false"));
    }

    // the engine, for a caller that checks problems itself (the crawl)
    void start() throws Exception {
        brain = new KataBrain(props);
    }

    synchronized void stop() {
        if (brain != null) brain.stopKataBrain();
        brain = null;
    }

    public void run() throws Exception {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        brain = new KataBrain(props);
        try {
            String path = props.getProperty("path");
            String ids = props.getProperty("id");
            if (path != null) {
                files(new File(path));
            } else if (ids != null) {
                ids(ids);
            } else {
                scan();
            }
        } finally {
            brain.stopKataBrain();
        }
    }

    private void files(File f) throws Exception {
        if (!f.exists()) {
            throw new IOException("no such file or directory: " + f);
        }
        List<File> files = new ArrayList<>();
        if (f.isDirectory()) {
            File[] listing = f.listFiles((d, name) -> name.endsWith(".sgf"));
            if (listing != null) files.addAll(Arrays.asList(listing));
            files.sort(Comparator.comparing(File::getName));
        } else {
            files.add(f);
        }
        for (File file : files) {
            String sgf = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Outcome o = check(file.getName().replaceAll("\\.sgf$", ""), sgf, null);
            log(o);
            if (o.finding != null) {
                show(o.finding);
                return;
            }
        }
        System.out.println("no missing variation found in " + files.size() + " problem" + (files.size() == 1 ? "" : "s"));
    }

    private void ids(String list) throws Exception {
        for (String s : list.split(",")) {
            Outcome o = site(Integer.parseInt(s.trim()));
            log(o);
            if (o.finding != null) {
                show(o.finding);
                return;
            }
        }
        System.out.println("no missing variation found");
    }

    // walk the site's problems by id, starting where the last scan stopped, until one has a
    // missing variation. the state is saved after every problem, so a stopped scan resumes
    // where it was and nothing is checked twice
    private void scan() throws Exception {
        File stateFile = new File(dir, "state.json");
        State state = stateFile.exists()
                ? RunRecorder.GSON.fromJson(Files.readString(stateFile.toPath()), State.class) : new State();
        if (props.getProperty("start") != null) state.next = Integer.parseInt(props.getProperty("start"));
        int maxChecks = Integer.parseInt(props.getProperty("scan.max", "0"));
        int maxMisses = Integer.parseInt(props.getProperty("scan.max_misses", "500"));
        long delay = Long.parseLong(props.getProperty("api.delay_ms", "100"));
        System.out.println("scanning goproblems.com from problem " + state.next
                + " for missing variations (life and death problems marked standard)");
        int misses = 0, checks = 0;
        while (true) {
            int id = state.next;
            Outcome o = site(id);
            log(o);
            state.next = id + 1;
            if ("skipped".equals(o.status)) state.skipped++;
            else state.checked++;
            if (o.finding != null) state.lastFound = o.problem;
            state.updated = LocalDateTime.now().toString();
            RunRecorder.writeAtomically(stateFile, RunRecorder.GSON.toJson(state));
            if (o.finding != null) {
                show(o.finding);
                System.out.println("the next scan starts at problem " + state.next);
                return;
            }
            misses = "no such problem".equals(o.reason) ? misses + 1 : 0;
            if (!"skipped".equals(o.status)) checks++;
            if (maxChecks > 0 && checks >= maxChecks) {
                System.out.println("checked " + checks + " problem" + (checks == 1 ? "" : "s")
                        + " without finding a missing variation; the next scan starts at problem " + state.next);
                return;
            }
            if (misses >= maxMisses) {
                System.out.println("no problems at the last " + misses + " ids, so this looks like the end of the"
                        + " database; the next scan starts at problem " + state.next);
                return;
            }
            if (delay > 0) Thread.sleep(delay);
        }
    }

    // fetch a problem from the site and check it, if it qualifies
    private Outcome site(int id) throws Exception {
        Outcome o = new Outcome();
        o.problem = "gp-" + id;
        Problem p = fetch(id);
        String why = p == null ? "no such problem" : unsuitable(p);
        if (why != null) {
            o.status = "skipped";
            o.reason = why;
            System.out.println(o.problem + ": skipped, " + why);
            return o;
        }
        return check(o.problem, p.sgf, p.rank == null ? null : p.rank.value + " " + p.rank.unit);
    }

    // why a problem from the site is not checked, or null when it is
    static String unsuitable(Problem p) {
        String genre = p.genre != null ? p.genre : p.specificGenre;
        if (!"life and death".equalsIgnoreCase(genre)) return "genre " + genre;
        if (!Boolean.TRUE.equals(p.isStandard)) return "not marked standard";
        if (p.sgf == null || p.sgf.isBlank()) return "no sgf";
        return null;
    }

    // a problem from the site, or null when there is none to have at that id
    private Problem fetch(int id) throws Exception {
        ApiClient api = new ApiClient();
        String url = api.buildUrl("api.problem.details", Map.of("id", String.valueOf(id)), null, props);
        String key = props.getProperty("apikey");
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
                conn.setRequestProperty("Accept", "application/json");
                if (key != null && !key.isBlank()) conn.setRequestProperty("X-Api-Key", key);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                int code = conn.getResponseCode();
                if (code == 404 || code == 403 || code == 410) return null;
                if (code >= 200 && code < 300) {
                    return RunRecorder.GSON.fromJson(api.readResponseContent(conn, code), Problem.class);
                }
                last = new IOException("problem " + id + ": http " + code);
            } catch (IOException e) {
                last = e;
            }
            Thread.sleep(2000L * attempt);
        }
        throw last;
    }

    // check one problem; a problem that cannot be checked is an outcome, not an exception
    Outcome check(String name, String sgf, String difficulty) {
        return check(name, sgf, difficulty, null);
    }

    // heading: a line for the console above the problem's own, or null (the crawl's progress)
    Outcome check(String name, String sgf, String difficulty, String heading) {
        Outcome o = new Outcome();
        o.problem = name;
        long start = System.currentTimeMillis();
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                o.finding = find(name, sgf, difficulty, heading, o);
                if (o.status == null) o.status = o.finding != null ? "found" : "clean";
                break;
            } catch (Exception e) {
                e.printStackTrace();
                o.status = "error";
                o.reason = e.getMessage() == null ? e.toString() : e.getMessage();
                if (brain.isAlive()) break;
                // katago died: restart it and try the problem once more
                System.out.println("katago died, restarting the engine");
                brain.stopKataBrain();
                try {
                    brain = new KataBrain(props);
                } catch (Exception restart) {
                    throw new RuntimeException("cannot restart katago", restart);
                }
                o.positions = 0;
                o.status = null;
                o.reason = null;
                o.findings.clear();
            }
        }
        o.seconds = (System.currentTimeMillis() - start) / 1000;
        System.out.println(name + ": " + o.status + (o.reason != null ? " (" + o.reason + ")" : "")
                + ", " + o.positions + " position" + (o.positions == 1 ? "" : "s") + " checked"
                + (o.koSkipped > 0 ? " (" + o.koSkipped + " not judged for a ko)" : "") + " in " + o.seconds + " s");
        return o;
    }

    private Finding find(String name, String sgf, String difficulty, String heading, Outcome o) throws Exception {
        System.out.println();
        if (heading != null) System.out.println(heading);
        System.out.println("=== " + name + (difficulty != null ? " (" + difficulty + ")" : "") + " ===");
        // a problem on a smaller board is checked, and written out, in the matching corner of
        // a full one
        SmallBoard.Embedded embedded;
        try {
            embedded = SmallBoard.toNineteen(sgf);
        } catch (IllegalArgumentException e) {
            o.status = "skipped";
            o.reason = e.getMessage();
            return null;
        }
        if (embedded.moved()) {
            System.out.println("a " + embedded.size() + "x" + embedded.size() + " board, played in the corner of a 19x19 one"
                    + " (columns +" + embedded.dx() + ", rows +" + embedded.dy() + " from the top)");
            sgf = embedded.sgf();
        }
        Node root = new Parser().parse(sgf);
        if (difficulty == null) difficulty = ProblemRedoer.difficulty(root);
        List<MovePath> refs = MovePath.extractRightPaths(root);
        if (refs.isEmpty()) {
            o.status = "skipped";
            o.reason = "no correct line";
            return null;
        }
        Judging judging = judging(brain, root, refs);
        ProblemIsolator.IsolatedProblem iso = judging.iso();
        if (iso.stakes.isEmpty()) {
            // with nothing at stake no move can fail, and a line has nothing to settle: the
            // isolation does not read the problem
            o.status = "skipped";
            o.reason = "nothing at stake in the isolation";
            return null;
        }
        for (Position pos : authorPositions(refs, IsolationRecord.colorLetter(iso.solverColor))) {
            o.positions++;
            if (skipKo) {
                Node at;
                try {
                    at = Positions.play(iso, pos.at());
                } catch (IllegalArgumentException e) {
                    at = null; // findAt reports it
                }
                if (at != null && KoLines.koIn(at, pos.lines()) != null) {
                    System.out.println("  " + (pos.at().isEmpty() ? "at the start" : "after " + String.join(" ", pos.at()))
                            + ": ko, not judged (the problem's line goes through a ko)");
                    o.koSkipped++;
                    continue;
                }
            }
            Finding f = findAt(name, sgf, root, judging, pos);
            if (f != null) {
                f.difficulty = difficulty;
                if (allPositions) {
                    o.findings.add(f);
                    continue;
                }
                try {
                    f.review = writeReview(name, sgf, f);
                } catch (Exception e) {
                    System.out.println("could not add the finding to the review run: " + e.getMessage());
                }
                return f;
            }
        }
        return o.findings.isEmpty() ? null : o.findings.get(0);
    }

    // where the problem disagrees with what the position allows at one position, or null
    // isolates a problem and builds what judging its moves needs. the plain fortress board:
    // kos are settled by the RegimeWalker, which plays them out with one side or the other
    // winning every ko, not by threats on the board. the level check (cmd=levels) judges
    // with this too
    Judging judging(KataBrain brain, Node root, List<MovePath> refs) throws Exception {
        Properties isolation = new Properties();
        isolation.putAll(props);
        isolation.setProperty("redo.ko_threats", "false");
        ProblemIsolator isolator = new ProblemIsolator(isolation, brain);
        ProblemIsolator.IsolatedProblem iso = isolator.isolate(root);
        DecisionFeatureExtractor extractor = new DecisionFeatureExtractor(props, brain, iso, params);
        // what is at stake when the solver wins its kos, with the author's lines among those
        // played out: hints at what may be at stake, never at what a move comes to
        List<List<String>> lines = new ArrayList<>();
        for (MovePath ref : refs) lines.add(ProblemRecord.moves(ref));
        isolator.settleUnderKos(iso, new RegimeWalker(iso, extractor, new StatusJudge(iso, params.statusStones)), lines);
        StatusJudge judge = new StatusJudge(iso, params.statusStones);
        extractor.setJudge(judge);
        RegimeWalker walker = new RegimeWalker(iso, extractor, judge);
        return new Judging(iso, extractor, judge, walker,
                new MoveClassifier(iso, walker, visits, replyVisits, verifyVisits, replyDist),
                new MoveClassifier(iso, walker, verifyVisits, visits, 2 * verifyVisits, replyDist));
    }

    private Finding findAt(String name, String sgf, Node root, Judging j, Position pos) throws Exception {
        ProblemIsolator.IsolatedProblem iso = j.iso();
        String where = pos.at().isEmpty() ? "at the start" : "after " + String.join(" ", pos.at());
        Node node;
        try {
            node = Positions.play(iso, pos.at());
        } catch (IllegalArgumentException e) {
            System.out.println("  " + where + ": cannot replay the position (" + e.getMessage() + ")");
            return null;
        }
        DecisionRecord rec = j.extractor().solver(node, pos.at(), pos.at().isEmpty() ? visitsRoot : visits);
        if (rec.error != null || rec.candidates.isEmpty()) {
            System.out.println("  " + where + ": no moves measured" + (rec.error != null ? " (" + rec.error + ")" : ""));
            return null;
        }
        Set<String> threatPoints = new HashSet<>();
        for (Point t : iso.threats) threatPoints.add(Intersection.toGTPloc(t.x, t.y, 19));

        // the moves worth judging: every candidate that does not fail outright on the plain
        // board (a move that reaches its result whoever wins the kos reaches it there too), and
        // the author's own moves whatever they did
        List<String> judged = new ArrayList<>();
        List<String> failedPlain = new ArrayList<>();
        for (CandidateFeatures c : rec.candidates) {
            if (c.afterLead == null || c.point().equals("pass") || threatPoints.contains(c.point())) continue;
            if (plainStatus(j, node, c.move) == StatusJudge.Status.FAIL) failedPlain.add(c.move);
            else judged.add(c.move);
        }
        for (String m : pos.correct()) {
            if (!judged.contains(m)) judged.add(m);
            failedPlain.remove(m);
        }
        // the author's own moves from elsewhere on its correct lines: a different move order is
        // a common way for a problem to miss a correct answer (gp-62070's R19, the author's
        // first move, works again later), and katago's candidates do not always include them
        Set<String> fromAuthor = new LinkedHashSet<>();
        for (String m : authorMovesElsewhere(root, pos.at(), IsolationRecord.colorLetter(iso.solverColor))) {
            if (judged.contains(m) || failedPlain.contains(m)) continue;
            Point p = Intersection.gtp2point(m.substring(2));
            if (node.board.board[p.x][p.y].stone != Intersection.EMPTY || !node.legalMove(p)) continue;
            fromAuthor.add(m);
            if (plainStatus(j, node, m) == StatusJudge.Status.FAIL) failedPlain.add(m);
            else judged.add(m);
        }
        // what the file says beyond right and wrong (article/bestpractices): with FORCE on the
        // position the solver can only play the moves the file has, so those are the ones
        // judged, and a move tagged NOTTHIS is outlawed there, so it is not judged at all
        Set<String> outlawed = outlawed(root, pos.at());
        if (forced(root, pos.at())) {
            judged.clear();
            failedPlain.clear();
            judged.addAll(sgfMoves(root, pos.at()));
            System.out.println("  " + where + ": FORCE, only the problem's own moves can be played");
        }
        if (!outlawed.isEmpty()) {
            judged.removeAll(outlawed);
            failedPlain.removeAll(outlawed);
            System.out.println("  " + where + ": NOTTHIS outlaws " + String.join(", ", outlawed.stream().map(MissingVariationFinder::pt).toList()));
        }
        // the author's moves first, since they are the likeliest to show the best level; once
        // a level is reached, a move the problem does not accept only matters if it reaches it
        // too (the author's own are always judged in full, for what they fall short of)
        List<String> order = new ArrayList<>(pos.correct());
        order.retainAll(judged);
        for (String m : judged) if (!order.contains(m)) order.add(m);
        Map<String, MoveClassifier.Verdict> verdicts = new LinkedHashMap<>();
        for (String m : order) verdicts.put(m, classify(j, pos, m, verdicts));
        MoveClassifier.Level best = bestLevel(j.quick(), verdicts);
        // a move that fails on the plain board can still win a ko there for the solver, but
        // reach nothing better: when nothing reaches more than a ko, those are judged too
        if (j.quick().rank(best) <= j.quick().rank(MoveClassifier.Level.KO)) {
            for (String m : failedPlain) verdicts.put(m, classify(j, pos, m, verdicts));
            best = bestLevel(j.quick(), verdicts);
        }
        System.out.println("  " + where + ": " + summary(j.quick(), verdicts));
        if (best == MoveClassifier.Level.FAIL) {
            System.out.println("  " + where + ": skipped, no move reaches anything even winning every ko, so the isolation"
                    + " misreads the position");
            return null;
        }

        // a position the solver may leave and still reach the best level is settled: every
        // move that does not throw the result away reaches it there, so none is missing
        // (gp-62860 after B E2, W E3, B F2, W F1: E1 and D2 are miai, and black may play
        // elsewhere once and keep the seki: twenty points around white's group keep it at a
        // quick look)
        MoveClassifier.Level top = best;
        boolean settled = false;
        if (verdicts.values().stream().anyMatch(v -> !pos.correct().contains(v.move) && v.level == top)) {
            String away = elsewhere(iso);
            if (away != null && j.quick().rank(j.quick().classify(pos.at(), away).level) >= j.quick().rank(best)) {
                settled = true;
                System.out.println("  " + where + ": settled, playing elsewhere reaches " + level(best) + " too, so nothing is missing");
            }
        }

        // correct moves the problem lacks, and moves it accepts that fall short, each judged
        // again with the deeper search before it counts
        List<MoveClassifier.Verdict> missing = new ArrayList<>();
        List<MoveClassifier.Verdict> wrong = new ArrayList<>();
        for (MoveClassifier.Verdict v : verdicts.values()) {
            boolean accepted = pos.correct().contains(v.move);
            if (!accepted && v.level == best && !settled) {
                MoveClassifier.Verdict deep = j.deep().classify(pos.at(), v.move);
                if (deep.level == best) missing.add(deep);
                else System.out.println("  " + where + ": " + pt(v.move) + " looked " + level(best) + " but the deeper look says "
                        + level(deep.level) + refuted(deep));
            } else if (accepted && j.quick().rank(v.level) < j.quick().rank(best)) {
                MoveClassifier.Verdict deep = j.deep().classify(pos.at(), v.move);
                if (j.quick().rank(deep.level) < j.quick().rank(best)) wrong.add(deep);
                else System.out.println("  " + where + ": the problem's " + pt(v.move) + " looked " + level(v.level)
                        + " but the deeper look says " + level(deep.level));
            }
        }
        if (missing.isEmpty() && wrong.isEmpty()) {
            nothingMissing(where, best, pos);
            return null;
        }

        Finding f = new Finding();
        f.problem = name;
        f.at = pos.at();
        f.accepted.addAll(pos.correct());
        f.level = best;
        f.iso = iso;
        // the position as redo's rule sees it, for the review tool
        ChoiceRule.Choice choice = ChoiceRule.chooseSolver(rec, params, iso.stakes);
        rec.chose = choice.move;
        rec.alternatives = new ArrayList<>(choice.also);
        rec.reason = choice.reason;
        f.decision = rec;
        Set<String> inFile = sgfMoves(root, pos.at());
        // the moves that reach the best level here: a found move that only puts an exchange in
        // before one of them is no answer of its own
        Set<String> working = new HashSet<>();
        for (MoveClassifier.Verdict v : verdicts.values()) if (v.level == top) working.add(v.move);
        for (MoveClassifier.Verdict v : missing) {
            String worse = best == MoveClassifier.Level.KO ? worseKo(j, node, pos, verdicts, v) : null;
            if (worse != null) {
                System.out.println("  " + where + ": " + pt(v.move) + " " + worse + ", so it is not suggested");
                continue;
            }
            FoundMove m = new FoundMove();
            m.move = v.move;
            m.level = v.level;
            m.line = withoutExchanges(v.line, iso);
            CandidateFeatures c = rec.candidate(v.move);
            if (c != null) {
                m.source = c.source;
                m.prior = c.prior;
                m.visits = c.visits;
                m.humanPolicy = c.humanPolicy;
                m.stakeDist = c.stakeDist;
            } else if (fromAuthor.contains(v.move)) {
                m.source = "author";
            }
            m.inFileAsWrong = inFile.contains(v.move);
            System.out.println("  " + where + ": " + pt(v.move) + " reaches " + level(best) + " too, generating its line");
            m.lines.addAll(linesFrom(iso, node, pos.at(), List.of(), v.move, m.decisions));
            // and the solver's way through each of the problem's own answers to it
            List<String> afterMove = new ArrayList<>(pos.at());
            afterMove.add(v.move);
            for (String answer : sgfMoves(root, afterMove)) {
                boolean covered = m.lines.stream().anyMatch(l -> l.size() > 1 && l.get(1).equals(answer));
                if (!covered) m.lines.addAll(linesFrom(iso, node, pos.at(), List.of(v.move, answer), null, m.decisions));
            }
            String not = insertedExchange(iso, node, m.lines.isEmpty() ? List.of() : m.lines.get(0), working);
            if (not == null) not = lineFallsShort(j, pos, v, m.lines);
            if (not != null) {
                System.out.println("  " + where + ": " + pt(v.move) + " " + not + ", so it is not suggested");
                continue;
            }
            m.confidence = confidence(j, root, node, pos, rec, best, verdicts, v, m.lines, true);
            f.moves.add(m);
        }
        for (MoveClassifier.Verdict v : wrong) {
            WrongMove w = new WrongMove();
            w.move = v.move;
            w.level = v.level;
            w.refutation = v.refutation;
            w.line = withoutExchanges(v.line, iso);
            w.confidence = confidence(j, root, node, pos, rec, best, verdicts, v, List.of(), false);
            f.wrong.add(w);
        }
        if (f.moves.isEmpty() && f.wrong.isEmpty()) {
            nothingMissing(where, best, pos);
            return null;
        }
        if (!allPositions) {
            f.sgf = writeSgf(name, sgf, f).getPath();
            Files.writeString(new File(dir, name + "_missing.json").toPath(), RunRecorder.GSON.toJson(f) + "\n");
        }
        return f;
    }

    // how far to trust a suggestion (Confidence): how its verdict was reached, what the problem
    // file says about the move, and what the isolation had to adjust. missing: a correct move
    // the problem lacks, with its generated lines; else a move it accepts that falls short
    private static Confidence confidence(Judging j, Node root, Node node, Position pos, DecisionRecord rec,
                                         MoveClassifier.Level best, Map<String, MoveClassifier.Verdict> verdicts,
                                         MoveClassifier.Verdict v, List<List<String>> lines, boolean missing) throws Exception {
        Confidence.Evidence e = new Confidence.Evidence();
        e.missing = missing;
        e.kos = v.kos;
        e.searched = v.searched;
        e.margin = v.margin;
        List<String> afterMove = new ArrayList<>(pos.at());
        afterMove.add(v.move);
        // a ko the line does not show: the position comes right after a ko capture, or the
        // move itself takes a ko
        Point p = Intersection.gtp2point(v.move.substring(2));
        e.koSpot = node.getKo() != null || takesKo(node, p);
        if (missing) {
            // a move far from every stone, or out in the open, that still keeps the status
            // usually means the position was settled already
            e.distance = nearestStone(j.iso(), node, p);
            e.outside = emptyShots(j.iso(), node, p) >= 3;
            e.authorRejects = sgfMoves(root, pos.at()).contains(v.move);
            e.katagoFirst = !rec.candidates.isEmpty() && rec.candidates.get(0).move.equals(v.move)
                    && "katago".equals(rec.candidates.get(0).source);
            e.authorAgrees = pos.correct().stream().allMatch(m -> verdicts.containsKey(m) && verdicts.get(m).level == best);
            // the author's correct lines: the positions on them, and the moves they play
            Set<String> authorPositions = new HashSet<>();
            for (MovePath ref : MovePath.extractRightPaths(root)) {
                List<String> moves = ProblemRecord.moves(ref);
                for (int i = 0; i < moves.size(); i++) {
                    if (moves.get(i).equals(v.move) && !moves.subList(0, i).equals(pos.at())) e.authorPlaysElsewhere = true;
                    String key = j.walker().zoneKeyAfter(moves.subList(0, i + 1));
                    if (key != null) authorPositions.add(key);
                }
            }
            List<List<String>> found = new ArrayList<>();
            found.add(v.line);
            for (List<String> l : lines) {
                List<String> full = new ArrayList<>(pos.at());
                full.addAll(l);
                found.add(full);
            }
            for (List<String> l : found) {
                for (int k = afterMove.size(); k <= l.size() && !e.transposes; k++) {
                    String key = j.walker().zoneKeyAfter(l.subList(0, k));
                    if (key != null && authorPositions.contains(key)) e.transposes = true;
                }
            }
        } else {
            e.authorRejects = v.refutation != null && sgfMoves(root, afterMove).contains(v.refutation);
        }
        for (String w : j.iso().warnings) {
            if (w.startsWith("stakes only settled")) e.isolation.add("the stakes only settled on a deeper retry");
            else if (w.startsWith("no stones seem to be at stake")) e.isolation.add("no stones seemed to be at stake");
            else if (w.startsWith("komi rebalanced")) e.isolation.add("komi was rebalanced");
            else if (w.startsWith("with the solver winning every ko")) e.isolation.add("the stakes were settled with the solver winning every ko");
            else if (w.startsWith("only the opponent's area swings")) e.isolation.add("only the opponent's area seemed to be at stake, as in a seki");
        }
        return Confidence.of(e);
    }

    // the solver playing elsewhere: a move in its own fill, where it is worth nothing (the
    // last such point, since the ko exchanges take the first), or null when there is none
    static String elsewhere(ProblemIsolator.IsolatedProblem iso) {
        List<Point> own = RegimeWalker.neutralPoints(iso, RegimeWalker.zone(iso), iso.solverColor);
        if (own.isEmpty()) return null;
        Point p = own.get(own.size() - 1);
        return IsolationRecord.colorLetter(iso.solverColor) + " " + Intersection.toGTPloc(p.x, p.y, 19);
    }

    private static void nothingMissing(String where, MoveClassifier.Level best, Position pos) {
        System.out.println("  " + where + ": nothing missing (best " + level(best) + "; the problem accepts "
                + String.join(", ", pos.correct().stream().map(MissingVariationFinder::pt).toList()) + ")");
    }

    // why a found move's ko is a worse one than the author's, or null. the site splits ko
    // results by who takes the ko first, and one the solver has to take back is the worse. on
    // the board without threats a ko goes to whoever takes it first, so a move that comes to
    // less there than the best of the author's ko answers has the worse ko (gp-62851: after
    // R19, W S18, black can only take back the ko white takes at T19, or settle for the seki
    // of B T19, where the author's R18 takes the ko first)
    private String worseKo(Judging j, Node node, Position pos, Map<String, MoveClassifier.Verdict> verdicts,
                           MoveClassifier.Verdict v) throws Exception {
        String whose = null;
        StatusJudge.Status theirs = null;
        for (String a : pos.correct()) {
            MoveClassifier.Verdict av = verdicts.get(a);
            if (av == null || av.level != MoveClassifier.Level.KO) continue;
            StatusJudge.Status s = plainStatus(j, node, a);
            if (theirs == null || s.compareTo(theirs) > 0) {
                theirs = s;
                whose = a;
            }
        }
        if (whose == null) return null;
        StatusJudge.Status mine = plainStatus(j, node, v.move);
        if (mine.compareTo(theirs) >= 0) return null;
        return "reaches ko too, but a worse one: on the board without threats, where a ko goes to whoever takes it first, it "
                + plain(mine) + " and " + pt(whose) + " " + plain(theirs);
    }

    private static String plain(StatusJudge.Status s) {
        return switch (s) {
            case SUCCESS -> "succeeds";
            case SEKI -> "comes to seki";
            case FAIL -> "fails";
        };
    }

    // why a found move is only an exchange put in before a move that works there anyway, or
    // null: its line goes on, after the opponent's answer, with a move that reaches the best
    // level at the position as it was. a stone the answer captures at once is lost for
    // nothing, and an exchange where neither stone takes part in the fight does not affect the
    // problem, so neither is another answer (adam's review: gp-62839's E1, which F1 captures
    // before the author's E3, and gp-62700's peep at M5, answered at M4 before the author's
    // H2). a move inside the fight before one of the author's is another order of play, and
    // counts (gp-62668's Q17 before O18, gp-62856's D4 before G1)
    static String insertedExchange(ProblemIsolator.IsolatedProblem iso, Node node, List<String> line, Set<String> working)
            throws Exception {
        if (line.size() < 3 || !working.contains(line.get(2))) return null;
        Point x = Intersection.gtp2point(line.get(0).substring(2));
        Point y = Intersection.gtp2point(line.get(1).substring(2));
        if (!node.legalMove(x)) return null;
        boolean xFights = inFight(iso, node, x);
        Node afterX = node.addBasicMove(x.x, x.y);
        try {
            if (!afterX.legalMove(y)) return null;
            boolean yFights = inFight(iso, afterX, y);
            Node afterY = afterX.addBasicMove(y.x, y.y);
            String then = pt(line.get(2));
            if (afterY.board.board[x.x][x.y].stone == Intersection.EMPTY) {
                return "is captured at once by " + pt(line.get(1)) + ": a stone lost before " + then + ", which works without it";
            }
            if (!xFights && !yFights) {
                return "and the answer at " + pt(line.get(1)) + " are an exchange outside the fight before " + then
                        + ", which works without it";
            }
            return null;
        } finally {
            node.removeChildNode(afterX);
        }
    }

    // whether a point takes part in the fight: next to a stone at stake, or in an empty region
    // the stones close off (an eye space), rather than in the open ground that runs out to the
    // fill around the problem
    static boolean inFight(ProblemIsolator.IsolatedProblem iso, Node node, Point p) {
        for (Point s : iso.stakes) {
            if (Math.abs(s.x - p.x) + Math.abs(s.y - p.y) == 1 && node.board.board[s.x][s.y].stone != Intersection.EMPTY) return true;
        }
        boolean[][] seen = new boolean[19][19];
        Deque<Point> todo = new ArrayDeque<>();
        todo.add(p);
        seen[p.x][p.y] = true;
        while (!todo.isEmpty()) {
            Point c = todo.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = c.x + d[0], y = c.y + d[1];
                if (x < 0 || y < 0 || x >= 19 || y >= 19 || seen[x][y]) continue;
                if (iso.isFilled(x, y)) return false;
                seen[x][y] = true;
                if (node.board.board[x][y].stone == Intersection.EMPTY) todo.add(new Point(x, y));
            }
        }
        return true;
    }

    // why the generated lines do not show the level the move was judged to reach, or null. a
    // line ends where the generator sees the problem settled, and the generator plays without
    // threats, so it can settle for less than a ko the move reaches only by winning it. a
    // suggestion has to show what it claims (gp-62851: R19 reaches ko when black wins every
    // ko, but its line ended in the seki of B T19)
    private static String lineFallsShort(Judging j, Position pos, MoveClassifier.Verdict v, List<List<String>> lines)
            throws Exception {
        for (List<String> line : lines) {
            List<String> full = new ArrayList<>(pos.at());
            full.addAll(line);
            MoveClassifier.Level reached = j.quick().levelAfter(full);
            if (j.quick().rank(reached) < j.quick().rank(v.level)) {
                return "has a line that ends in " + level(reached) + ", short of " + level(v.level) + " (" + String.join(" ", line) + ")";
            }
        }
        return null;
    }

    // whether the move at the point takes a ko
    static boolean takesKo(Node node, Point p) throws Exception {
        if (!node.legalMove(p)) return false;
        Node child = node.addBasicMove(p.x, p.y);
        try {
            return child.getKo() != null;
        } finally {
            node.removeChildNode(child);
        }
    }

    // the distance from the point to the nearest stone of the problem, the fill aside
    static double nearestStone(ProblemIsolator.IsolatedProblem iso, Node node, Point p) {
        double best = 100;
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (node.board.board[x][y].stone == Intersection.EMPTY || iso.isFilled(x, y) || (x == p.x && y == p.y)) continue;
                best = Math.min(best, Math.hypot(x - p.x, y - p.y));
            }
        return best;
    }

    // how many of the four directions from the point run to the edge without meeting a stone
    // of the problem, the fill aside (as PathCreator.countEmptyShots counts): a point on the
    // edge counts one direction as met
    static int emptyShots(ProblemIsolator.IsolatedProblem iso, Node node, Point p) {
        int met = p.x == 0 || p.y == 0 || p.x == 18 || p.y == 18 ? 1 : 0;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            for (int x = p.x + d[0], y = p.y + d[1]; x >= 0 && y >= 0 && x < 19 && y < 19; x += d[0], y += d[1]) {
                if (node.board.board[x][y].stone != Intersection.EMPTY && !iso.isFilled(x, y)) {
                    met++;
                    break;
                }
            }
        }
        return Math.max(0, 4 - met);
    }

    // the solver's moves on the author's correct lines at other positions than at
    static Set<String> authorMovesElsewhere(Node root, List<String> at, String solver) {
        Set<String> out = new LinkedHashSet<>();
        for (MovePath ref : MovePath.extractRightPaths(root)) {
            List<String> moves = ProblemRecord.moves(ref);
            for (int i = 0; i < moves.size(); i++) {
                if (moves.get(i).startsWith(solver + " ") && !moves.subList(0, i).equals(at)) out.add(moves.get(i));
            }
        }
        return out;
    }

    // what the move comes to on the plain board, from the analysis the extractor already made
    private StatusJudge.Status plainStatus(Judging j, Node node, String move) throws Exception {
        Point p = Intersection.gtp2point(move.substring(2));
        if (!node.legalMove(p)) return StatusJudge.Status.FAIL;
        Node child = node.addBasicMove(p.x, p.y);
        try {
            return j.judge().status(child, j.extractor().after(child));
        } finally {
            node.removeChildNode(child);
        }
    }

    // a move's quick verdict, held to the best level so far unless the problem accepts it
    private static MoveClassifier.Verdict classify(Judging j, Position pos, String move,
                                                   Map<String, MoveClassifier.Verdict> sofar) throws Exception {
        boolean accepted = pos.correct().contains(move);
        MoveClassifier.Level floor = accepted || sofar.isEmpty() ? null : bestLevel(j.quick(), sofar);
        return j.quick().classify(pos.at(), move, floor);
    }

    private static MoveClassifier.Level bestLevel(MoveClassifier c, Map<String, MoveClassifier.Verdict> verdicts) {
        MoveClassifier.Level best = MoveClassifier.Level.FAIL;
        for (MoveClassifier.Verdict v : verdicts.values()) {
            if (c.rank(v.level) > c.rank(best)) best = v.level;
        }
        return best;
    }

    // the judged moves by level, best first, with what brings each one down
    private static String summary(MoveClassifier c, Map<String, MoveClassifier.Verdict> verdicts) {
        List<String> parts = new ArrayList<>();
        List<MoveClassifier.Level> levels = new ArrayList<>(List.of(MoveClassifier.Level.values()));
        levels.sort(Comparator.comparingInt(c::rank).reversed());
        for (MoveClassifier.Level l : levels) {
            List<String> moves = new ArrayList<>();
            for (MoveClassifier.Verdict v : verdicts.values()) {
                if (v.level == l && !v.capped) moves.add(pt(v.move) + (v.refutation != null ? " (" + pt(v.refutation) + ")" : ""));
            }
            if (!moves.isEmpty()) parts.add(level(l) + " " + String.join(", ", moves));
        }
        List<String> capped = new ArrayList<>();
        for (MoveClassifier.Verdict v : verdicts.values()) if (v.capped) capped.add(pt(v.move));
        if (!capped.isEmpty()) parts.add("short of the best " + String.join(", ", capped));
        return String.join("; ", parts);
    }

    static String level(MoveClassifier.Level l) {
        return switch (l) {
            case CLEAN -> "clean";
            case SEKI -> "seki";
            case KO -> "ko";
            case FAIL -> "failure";
        };
    }

    private static String refuted(MoveClassifier.Verdict v) {
        return v.refutation == null ? "" : " (" + pt(v.refutation) + " answers it)";
    }

    // a walk's line without the free ko exchanges, which are played in the fill
    private static List<String> withoutExchanges(List<String> line, ProblemIsolator.IsolatedProblem iso) {
        if (line == null) return null;
        boolean[][] zone = RegimeWalker.zone(iso);
        List<String> out = new ArrayList<>();
        for (String m : line) {
            Point p = Intersection.gtp2point(m.substring(2));
            if (zone[p.x][p.y]) out.add(m);
        }
        return out;
    }

    // every position on the author's correct lines where the solver is to play, root first,
    // with the moves the author accepts there
    static List<Position> authorPositions(List<MovePath> refs, String solver) {
        Map<String, Position> byKey = new LinkedHashMap<>();
        for (MovePath ref : refs) {
            List<String> moves = ProblemRecord.moves(ref);
            for (int i = 0; i < moves.size(); i++) {
                if (!moves.get(i).startsWith(solver + " ")) continue;
                List<String> at = new ArrayList<>(moves.subList(0, i));
                Position pos = byKey.computeIfAbsent(DecisionRecord.key(at),
                        k -> new Position(at, new LinkedHashSet<>(), new ArrayList<>()));
                pos.correct().add(moves.get(i));
                pos.lines().add(new ArrayList<>(moves.subList(i, moves.size())));
            }
        }
        List<Position> out = new ArrayList<>(byKey.values());
        out.sort(Comparator.comparingInt(p -> p.at().size()));
        return out;
    }

    // the moves the problem file has at a position, on correct lines or not
    static Set<String> sgfMoves(Node root, List<String> at) {
        return fileMoves(root, at, false);
    }

    // the moves the file outlaws at a position: NOTTHIS in a move's comment, which the site
    // takes as a point the solver may not play (article/bestpractices)
    static Set<String> outlawed(Node root, List<String> at) {
        return fileMoves(root, at, true);
    }

    // whether the file limits the solver to its own moves at a position: FORCE in the
    // comment of the position's node (article/bestpractices)
    static boolean forced(Node root, List<String> at) {
        Node cur = nodeAt(root, at);
        return cur != null && cur.forceMove;
    }

    private static Set<String> fileMoves(Node root, List<String> at, boolean onlyOutlawed) {
        Node cur = nodeAt(root, at);
        if (cur == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Node child : cur.babies) {
            MoveAction ma = child.getMoveAction();
            if (ma == null || ma.loc.x < 0 || ma.loc.x >= 19 || ma.loc.y < 0 || ma.loc.y >= 19) continue;
            if (onlyOutlawed && !child.notThis) continue;
            out.add(IsolationRecord.colorLetter(ma.stone) + " " + Intersection.toGTPloc(ma.loc.x, ma.loc.y, 19));
        }
        return out;
    }

    private static Node nodeAt(Node root, List<String> at) {
        Node cur = root;
        for (String m : at) {
            cur = cur.getChildWithMove(Intersection.gtp2point(m.substring(2)));
            if (cur == null) return null;
        }
        return cur;
    }

    // lines from a position, generated by the redo generator so they answer and end the way
    // redo's would: from after the given moves (the solver to play), with a first move
    // forced or not. the lines start with the given moves. the generator's decisions go to
    // decisions, at from the root (at is the position's)
    private List<List<String>> linesFrom(ProblemIsolator.IsolatedProblem iso, Node node, List<String> at,
                                         List<String> moves, String forced, List<DecisionRecord> decisions)
            throws Exception {
        Node cur = node;
        Node first = null;
        ProblemIsolator.IsolatedProblem from;
        try {
            for (String move : moves) {
                Point p = Intersection.gtp2point(move.substring(2));
                if (!cur.legalMove(p)) return List.of();
                cur = cur.addBasicMove(p.x, p.y);
                if (first == null) first = cur;
            }
            IsolationRecord sub = new IsolationRecord();
            sub.solver = IsolationRecord.colorLetter(iso.solverColor);
            sub.komi = iso.komi;
            sub.targetLead = iso.targetLead;
            for (Point s : iso.stakes) {
                if (cur.board.board[s.x][s.y].stone != Intersection.EMPTY) {
                    sub.stakes.add(Intersection.toGTPloc(s.x, s.y, 19));
                }
            }
            sub.rows = Positions.rows(cur, iso);
            if (!iso.threats.isEmpty()) {
                sub.threats = new ArrayList<>();
                for (Point t : iso.threats) sub.threats.add(Intersection.toGTPloc(t.x, t.y, 19));
            }
            from = sub.toIsolated();
        } finally {
            if (first != null) node.removeChildNode(first);
        }
        from.solvedKar = iso.solvedKar; // the stones at stake end up the same way from here
        // the scan of every point near the stakes is for the positions being checked; at every
        // turn of a generated line it would multiply the time a line takes. the line only
        // illustrates the move, whose level already holds against every answer, so the
        // opponent resists the way redo's lines do (redo.response_criterion) and the solver
        // keeps to its main choice
        Properties lineProps = new Properties();
        lineProps.putAll(props);
        lineProps.setProperty("redo.scan_dist", "-1");
        lineProps.setProperty("redo.max_solver_moves", "1");
        SolutionPathGenerator generator = new SolutionPathGenerator(lineProps, brain, from);
        if (forced != null) generator.setForcedFirstMove(Intersection.gtp2point(forced.substring(2)));
        for (DecisionRecord d : generator.generate().decisions) {
            List<String> full = new ArrayList<>(at);
            full.addAll(moves);
            full.addAll(d.at);
            DecisionRecord rooted = d.copyAt(full);
            rooted.chose = d.chose;
            rooted.alternatives = d.alternatives;
            rooted.reason = d.reason;
            decisions.add(rooted);
        }
        // a line that goes on into the ko threats is cut where the ko starts, the way a
        // problem's own ko line ends
        Set<String> threatPoints = new HashSet<>();
        for (Point t : from.threats) threatPoints.add(Intersection.toGTPloc(t.x, t.y, 19));
        List<List<String>> lines = new ArrayList<>();
        for (MovePath line : MovePath.extractRightPaths(from.problem)) {
            List<String> full = new ArrayList<>(moves);
            for (String m : ProblemRecord.moves(line)) {
                if (threatPoints.contains(m.substring(2))) break;
                full.add(m);
            }
            if (!lines.contains(full)) lines.add(full);
        }
        if (lines.isEmpty()) {
            List<String> only = new ArrayList<>(moves);
            if (forced != null) only.add(forced);
            if (!only.isEmpty()) lines.add(only);
        }
        return lines;
    }

    // the finding as a problem of a run the review tool reads (cmd=review
    // run=<missing.dir>/review), next to the findings before it: the author's tree with the
    // found lines added (they show as generated only), the decision at the position with
    // every candidate measured there, and the generator's decisions behind the lines
    private File writeReview(String name, String sgf, Finding f) throws Exception {
        File reviewDir = new File(dir, "review");
        if (!reviewDir.isDirectory() && !reviewDir.mkdirs()) throw new IOException("cannot create " + reviewDir);
        File sgfFile = new File(reviewDir, name + ".sgf");
        Files.writeString(sgfFile.toPath(), sgf);

        ProblemRecord rec = new ProblemRecord();
        rec.problem = name;
        rec.file = sgfFile.getPath();
        rec.sha = RunRecorder.sha1(sgf.getBytes(StandardCharsets.UTF_8));
        rec.difficulty = f.difficulty;
        rec.isolation = IsolationRecord.from(f.iso);
        for (MovePath ref : MovePath.extractRightPaths(new Parser().parse(sgf))) rec.refs.add(ProblemRecord.moves(ref));
        rec.generatedLines.addAll(rec.refs);
        Set<String> keys = new HashSet<>();
        rec.decisions.add(f.decision);
        keys.add(f.decision.key());
        String where = f.at.isEmpty() ? "at the start" : "after " + String.join(" ", f.at);
        for (FoundMove m : f.moves) {
            for (List<String> line : m.lines) {
                List<String> full = new ArrayList<>(f.at);
                full.addAll(line);
                if (!rec.generatedLines.contains(full)) rec.generatedLines.add(full);
            }
            // the position's own decision has every candidate; the generator's there has the forced move only
            for (DecisionRecord d : m.decisions) {
                if (keys.add(d.key())) rec.decisions.add(d);
            }
            rec.notes.add("missing variation " + where + ": " + pt(m.move) + " reaches " + level(m.level)
                    + ", the best result there; confidence " + m.confidence.explained());
        }
        for (WrongMove w : f.wrong) {
            rec.notes.add("the problem accepts " + pt(w.move) + " " + where + ", but it only reaches " + level(w.level)
                    + " where " + level(f.level) + " is possible" + (w.refutation != null ? ": " + pt(w.refutation) + " answers it" : "")
                    + "; confidence " + w.confidence.explained());
        }
        rec.generated = rec.generatedLines.get(rec.generatedLines.size() - 1);
        rec.tree = TreeComparison.compare(rec.generatedLines, rec.refs);
        RunRecorder.writeAtomically(new File(reviewDir, name + ".json"), RunRecorder.GSON.toJson(rec));

        // the manifest lists every problem with a finding, latest last
        File manifestFile = new File(reviewDir, "manifest.json");
        RunRecorder.Manifest manifest = manifestFile.isFile() ? RunRecorder.readManifest(reviewDir) : new RunRecorder.Manifest();
        if (manifest.id == null) {
            manifest.id = "missing-variations";
            manifest.started = LocalDateTime.now().toString();
        }
        manifest.finished = LocalDateTime.now().toString();
        manifest.commit = RunRecorder.gitCommit();
        manifest.katago = props.getProperty("katago");
        manifest.model = props.getProperty("kata.model");
        manifest.humanModel = props.getProperty("kata.human_model");
        manifest.kataConfig = props.getProperty("kata.config");
        manifest.problems.remove(name);
        manifest.problems.add(name);
        RunRecorder.writeAtomically(manifestFile, RunRecorder.GSON.toJson(manifest));
        return reviewDir;
    }

    // the problem with the found lines added at their position, for a look in any sgf editor:
    // the first new move carries a MISSING VARIATION comment and every line ends RIGHT
    private File writeSgf(String name, String sgf, Finding f) throws Exception {
        Node root = new Parser().parse(sgf);
        Node at = root;
        for (String m : f.at) {
            at = at.getChildWithMove(Intersection.gtp2point(m.substring(2)));
            if (at == null) throw new IOException("the position is not in the problem file: " + String.join(" ", f.at));
        }
        for (FoundMove m : f.moves) {
            Map<String, Node> added = new HashMap<>();
            for (List<String> line : m.lines) {
                Node cur = at;
                List<String> path = new ArrayList<>();
                for (String move : line) {
                    path.add(move);
                    String key = String.join(" ", path);
                    Node next = added.get(key);
                    if (next == null) {
                        Point p = Intersection.gtp2point(move.substring(2));
                        // a move the file already has (on a wrong line) is reused, not doubled,
                        // and is no longer wrong
                        next = cur.getChildWithMove(p);
                        if (next == null) next = cur.addBasicMove(p.x, p.y);
                        if (next.result == Intersection.WRONG) next.result = 0;
                        if (path.size() == 1) addComment(next, comment(m));
                        added.put(key, next);
                    }
                    cur = next;
                }
                cur.result = Intersection.RIGHT;
            }
        }
        // a move the problem accepts that falls short keeps its place, with a comment saying so
        for (WrongMove w : f.wrong) {
            Node node = at.getChildWithMove(Intersection.gtp2point(w.move.substring(2)));
            if (node != null) addComment(node, comment(w, f.level));
        }
        File out = new File(dir, name + "_missing.sgf");
        Files.writeString(out.toPath(), "(" + root.outputSGF(true) + ")\n");
        return out;
    }

    // a node keeps a single comment: new text goes in front of any it has
    private static void addComment(Node node, String text) {
        for (Object a : node.acts) {
            if (a instanceof CommentAction ca) {
                ca.comment = ca.comment == null || ca.comment.isBlank() ? text : text + "\n\n" + ca.comment;
                return;
            }
        }
        node.addAct(new CommentAction(text));
    }

    private static String comment(FoundMove m) {
        return "MISSING VARIATION (autoprob): " + pt(m.move) + " reaches " + level(m.level)
                + " here too, the best result in this position, so it should be accepted. Confidence: "
                + m.confidence.explained();
    }

    private static String comment(WrongMove w, MoveClassifier.Level best) {
        return "AUTOPROB: " + pt(w.move) + " only reaches " + level(w.level) + " here, where " + level(best)
                + " is possible" + (w.refutation != null ? " (" + pt(w.refutation) + " answers it)" : "") + ". Confidence: "
                + w.confidence.explained();
    }

    private static void printConfidence(Confidence c) {
        System.out.println("  confidence: " + c.text());
        for (String r : c.reasons) System.out.println("    " + r);
    }

    private void show(Finding f) {
        String bar = "=".repeat(72);
        String side = IsolationRecord.colorLetter(f.iso.solverColor).equals("B") ? "black" : "white";
        System.out.println();
        System.out.println(bar);
        System.out.println((f.moves.isEmpty() ? "PROBLEM ERROR" : "MISSING VARIATION") + " in " + f.problem
                + (f.difficulty != null ? " (" + f.difficulty + ")" : ""));
        System.out.println("position: " + (f.at.isEmpty() ? "the start" : "after " + String.join(" ", f.at))
                + ", " + side + " to play; the best result there is " + level(f.level));
        System.out.println("the problem accepts: " + String.join(", ", f.accepted.stream().map(MissingVariationFinder::pt).toList()));
        for (FoundMove m : f.moves) {
            System.out.println();
            System.out.println("also " + level(m.level) + ": " + pt(m.move));
            printConfidence(m.confidence);
            if (m.source != null) {
                System.out.println("  came up " + ("scan".equals(m.source) ? "in the scan of points near the stakes"
                        : "author".equals(m.source) ? "from the author's correct lines elsewhere in the problem"
                        : "as a " + m.source + " move") + ": katago prior "
                        + (m.prior == null ? "-" : new DecimalFormat("0.000").format(m.prior))
                        + (m.visits == null ? "" : ", " + m.visits + " visits")
                        + ", human policy " + (m.humanPolicy == null ? "-" : new DecimalFormat("0.00").format(m.humanPolicy))
                        + (m.stakeDist == null ? "" : ", " + m.stakeDist + " from the stones at stake"));
            }
            if (m.inFileAsWrong) {
                System.out.println("  the problem has " + pt(m.move) + ", but on a line that does not end RIGHT, so it is marked wrong");
            }
            if (m.line != null && m.line.size() > f.at.size()) {
                System.out.println("  played out: " + String.join(" ", m.line.subList(f.at.size(), m.line.size()).stream()
                        .map(MissingVariationFinder::pt).toList()));
            }
            for (List<String> line : m.lines) {
                System.out.println("  line: " + String.join(" ", line.stream().map(MissingVariationFinder::pt).toList()));
            }
        }
        for (WrongMove w : f.wrong) {
            System.out.println();
            System.out.println("the problem accepts " + pt(w.move) + ", but it only reaches " + level(w.level)
                    + (w.refutation != null ? ": " + pt(w.refutation) + " answers it" : ""));
            printConfidence(w.confidence);
            if (w.line != null && w.line.size() > f.at.size()) {
                System.out.println("  played out: " + String.join(" ", w.line.subList(f.at.size(), w.line.size()).stream()
                        .map(MissingVariationFinder::pt).toList()));
            }
        }
        System.out.println();
        System.out.println("wrote " + f.sgf + ": the problem with the missing lines added (commented MISSING VARIATION)"
                + (f.wrong.isEmpty() ? "" : " and the accepted moves that fall short commented"));
        if (f.review != null) {
            System.out.println("in the review tool, with the findings before it: bash gotool.sh cmd=review run=" + f.review.getPath());
        }
        System.out.println(bar);
    }

    private void log(Outcome o) throws IOException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("time", LocalDateTime.now().toString());
        row.put("problem", o.problem);
        row.put("status", o.status);
        if (o.reason != null) row.put("reason", o.reason);
        row.put("positions", o.positions);
        if (o.koSkipped > 0) row.put("koSkipped", o.koSkipped);
        row.put("seconds", o.seconds);
        if (o.finding != null) {
            row.put("at", String.join(" ", o.finding.at));
            row.put("level", level(o.finding.level));
            row.put("found", o.finding.moves.stream().map(m -> m.move).toList());
            if (!o.finding.wrong.isEmpty()) row.put("wrong", o.finding.wrong.stream().map(w -> w.move).toList());
        }
        Files.writeString(new File(dir, "log.jsonl").toPath(), new Gson().toJson(row) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String pt(String move) {
        return move.substring(2);
    }
}
