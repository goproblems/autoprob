package autoprob.validate;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

// cmd=crawl: works through goproblems.com's problems, newest first, checks every life and death
// problem marked standard for correct moves it lacks, at the start or deeper in its tree, and
// writes a suggested edit for each problem it finds them in. nothing goes to the site from
// here: the suggestions are reviewed (cmd=crawl-review) and sent (cmd=crawl-submit) later.
//
// it runs in crawl.dir (crawl/) and can be stopped at any time. what it made of each problem is
// in problems.jsonl (CrawlIndex), so the next run goes on where it was and never judges a
// problem twice. each run starts from the newest problem on the site, so problems added since
// come first, and goes down past what is done. crawl.max ends a run after that many problems
// judged, crawl.hours after that long. a problem whose sgf changed on the site after its
// suggestion was made (stale, from crawl-submit) is judged again first.
//
// a problem the site is not ready with is deferred, not skipped: a new one not marked standard
// yet, or one with a suggestion open. the site's editors mark a problem standard days or weeks
// after it is made, so a skip there loses it (on 2026-10-27 the crawl skipped 34 problems
// between gp-62866 and gp-62915 as not standard, and by 2026-11-22 the site had marked 21 of
// them). each run reads the deferred problems from the site again before anything else, a read
// each and no engine. one that is ready is judged like a new problem; one that is not stays as
// it is, with nothing written and only the count said. a problem still not standard
// crawl.wait_days (180) after it was made is skipped for good.
//
// the folder: problems.jsonl; sgf/gp-<id>.sgf, the problem as the site had it;
// findings/gp-<id>.json, the finder's findings; suggestions/gp-<id>.json (SuggestionDraft);
// state.json, counts for a look; lock, so two crawls do not share a folder (katago needs the
// memory)
public class Crawler {
    // the site and the finder as a run sees them, so the crawl can be tested without either
    interface Site {
        int newestId() throws Exception;

        // null when the site has no problem at the id
        JsonObject problem(int id) throws Exception;
    }

    interface Analyzer {
        // heading: a line for the console above the problem's own
        MissingVariationFinder.Outcome analyze(String name, String sgf, String difficulty, String heading) throws Exception;
    }

    static class State {
        int newest;   // the newest problem when the last run started
        Integer last; // the last problem looked at
        Map<String, Integer> counts = new TreeMap<>();
        int runs;
        String updated;
    }

    private final Properties props;
    private final File dir;
    private final int max;
    private final double hours;
    private final int maxAttempts;
    private final int waitDays;
    private final Set<String> recheck;
    private final List<Integer> only;
    private final boolean includeSandbox;
    // the time, for how long a run has gone and crawl.hours: a test sets its own
    LongSupplier clock = System::currentTimeMillis;

    public Crawler(Properties props) {
        this.props = props;
        this.dir = new File(props.getProperty("crawl.dir", "crawl"));
        this.max = Integer.parseInt(props.getProperty("crawl.max", "0"));
        this.hours = Double.parseDouble(props.getProperty("crawl.hours", "0"));
        // a problem the finder fails on is tried this many times in all
        this.maxAttempts = Integer.parseInt(props.getProperty("crawl.attempts", "2"));
        // a problem not marked standard is waited for until this many days after it was made
        this.waitDays = Integer.parseInt(props.getProperty("crawl.wait_days", "180"));
        // statuses to judge again when an older judging version gave them, e.g. found,clean
        this.recheck = Arrays.stream(props.getProperty("crawl.recheck", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        // just these problems, judged again whatever the index has for them, e.g. 62299
        this.only = Arrays.stream(props.getProperty("crawl.only", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt).toList();
        this.includeSandbox = Boolean.parseBoolean(props.getProperty("crawl.include_sandbox", "false"));
    }

    public void run() throws Exception {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        try (FileChannel channel = FileChannel.open(new File(dir, "lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = null;
            try {
                lock = channel.tryLock();
                if (lock == null) throw new IllegalStateException("another crawl is running in " + dir);
            } catch (IOException e) {
                System.out.println("cannot lock " + dir + " (" + e.getMessage() + "); make sure no other crawl uses it");
            }
            Properties finderProps = new Properties();
            finderProps.putAll(props);
            finderProps.setProperty("missing.all_positions", "true");
            finderProps.setProperty("missing.dir", new File(dir, "findings").getPath());
            MissingVariationFinder finder = new MissingVariationFinder(finderProps);
            finder.start();
            // a stop with ctrl-c still stops katago
            Thread hook = new Thread(finder::stop);
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                GoproblemsSite site = new GoproblemsSite(props);
                crawl(new Site() {
                    public int newestId() throws Exception {
                        return site.newestId();
                    }

                    public JsonObject problem(int id) throws Exception {
                        return site.problem(id);
                    }
                }, finder::check);
            } finally {
                finder.stop();
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // shutting down already
                }
                if (lock != null) lock.release();
            }
        }
    }

    void crawl(Site site, Analyzer analyzer) throws Exception {
        for (String d : List.of("sgf", "findings", "suggestions")) new File(dir, d).mkdirs();
        CrawlIndex index = CrawlIndex.load(new File(dir, "problems.jsonl"));
        State state = readState();
        int newest = site.newestId();
        state.newest = newest;
        state.runs++;
        long start = clock.getAsLong();
        long end = hours > 0 ? start + (long) (hours * 3_600_000) : Long.MAX_VALUE;
        // problems changed on the site since their suggestion was made go first; a problem
        // whose suggestion went to the site is never judged again here
        List<Integer> stale = new ArrayList<>();
        for (CrawlIndex.Entry e : index.entries()) if (e.status.equals("stale")) stale.add(e.id);
        Set<Integer> submitted = submittedIds();
        // and before those, the problems the site was not ready with are read again, the newest
        // first
        List<Integer> deferred = new ArrayList<>();
        if (only.isEmpty()) {
            for (CrawlIndex.Entry e : index.entries()) if (e.status.equals("deferred") && !submitted.contains(e.id)) deferred.add(e.id);
            deferred.sort(Comparator.reverseOrder());
        }
        if (!only.isEmpty()) {
            System.out.println("judging " + only.stream().map(id -> "gp-" + id).collect(Collectors.joining(", ")) + " again, in " + dir);
        } else {
            System.out.println("crawling goproblems.com from problem " + newest + " down, in " + dir + ": " + index.entries().size()
                    + " looked at before" + (stale.isEmpty() ? "" : ", " + stale.size() + " changed on the site to judge again first")
                    + (deferred.isEmpty() ? "" : ", " + deferred.size()
                    + " of them deferred (not marked standard yet, or a suggestion open) and read again first"));
        }
        int judged = 0, nextStale = 0, nextOnly = 0, cursor = newest;
        // what this run has looked at so far, shown above each problem judged
        int crawled = 0;
        Map<String, Integer> thisRun = new TreeMap<>();
        // the deferred problems the site is still not ready with: the run passes them over
        Set<Integer> waiting = new HashSet<>();
        if (!deferred.isEmpty()) {
            int ready = 0, other = 0, unread = 0;
            for (int id : deferred) {
                CrawlIndex.Entry before = index.get(id), now;
                try {
                    now = ruledOut(entry(id, before), site.problem(id));
                } catch (Exception ex) {
                    // not read this time: it waits on as it is
                    waiting.add(id);
                    unread++;
                    continue;
                }
                // ready: judged like a new problem, when the run comes to it below
                if (now == null) {
                    ready++;
                    continue;
                }
                waiting.add(id);
                // as it was: nothing to write, and nothing to say but the count
                if (now.status.equals(before.status) && Objects.equals(now.reason, before.reason)) continue;
                // it came to something else: skipped for good, or deferred for another reason
                record(index, state, thisRun, now);
                crawled++;
                other++;
            }
            System.out.println(deferredLine(deferred.size(), ready, other, unread));
        }
        while (true) {
            if ((max > 0 && judged >= max) || clock.getAsLong() > end) {
                System.out.println("stopping after " + judged + " problem" + (judged == 1 ? "" : "s") + " judged; the next run goes on from here");
                break;
            }
            int id;
            if (!only.isEmpty()) {
                // crawl.only: those problems and no others, then the run ends; one whose
                // suggestion went to the site is still left alone
                if (nextOnly >= only.size()) break;
                id = only.get(nextOnly++);
                if (submitted.contains(id)) {
                    System.out.println("gp-" + id + ": its suggestion went to the site, so it is not judged again");
                    continue;
                }
            } else if (nextStale < stale.size()) {
                id = stale.get(nextStale++);
            } else {
                while (cursor >= 1 && (submitted.contains(cursor) || stale.contains(cursor) || waiting.contains(cursor)
                        || index.done(cursor, maxAttempts, recheck, MissingVariationFinder.JUDGING_VERSION))) cursor--;
                if (cursor < 1) {
                    System.out.println("every problem down to the first has been looked at");
                    break;
                }
                id = cursor--;
                state.last = id;
            }
            CrawlIndex.Entry e;
            try {
                e = look(id, site, analyzer, index.get(id), progress(crawled, thisRun, (clock.getAsLong() - start) / 1000));
            } catch (Exception ex) {
                // one problem going wrong does not stop the crawl
                ex.printStackTrace();
                e = error(entry(id, index.get(id)), String.valueOf(ex.getMessage()));
            }
            record(index, state, thisRun, e);
            if (e.version != null) judged++;
            crawled++;
        }
    }

    // what a problem came to goes into the index and the counts, with its line on the console
    private void record(CrawlIndex index, State state, Map<String, Integer> thisRun, CrawlIndex.Entry e) throws IOException {
        index.append(e);
        thisRun.merge(e.status, 1, Integer::sum);
        state.counts.merge(e.status, 1, Integer::sum);
        state.updated = LocalDateTime.now().toString();
        RunRecorder.writeAtomically(new File(dir, "state.json"), RunRecorder.GSON.toJson(state));
        System.out.println("gp-" + e.id + ": " + e.status + (e.reason != null ? " (" + e.reason + ")" : "")
                + (e.seconds != null ? " in " + e.seconds + " s" : ""));
    }

    // the line after the deferred problems were read again: how many of them the site is ready
    // with now, how many came to something else (each with a line of its own above), and how
    // many stay deferred, those the site could not be read for among them
    static String deferredLine(int all, int ready, int other, int unread) {
        return "deferred problems read again: " + ready + " can be judged now, "
                + (other > 0 ? other + " came to something else, " : "") + (all - ready - other) + " still deferred"
                + (unread > 0 ? " (" + unread + " of them could not be read)" : "");
    }

    // the line above each problem judged: how many problems this run has looked at so far, in
    // how long, and what they came to, the finds first
    static String progress(int crawled, Map<String, Integer> counts, long seconds) {
        List<String> parts = new ArrayList<>();
        for (String s : List.of("found", "unsuggested", "clean", "deferred", "skipped", "error")) {
            if (counts.containsKey(s)) parts.add(counts.get(s) + " " + s);
        }
        return "crawled so far this run: " + crawled + " in " + duration(seconds)
                + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")");
    }

    // "40 s", "12 min", "2 h 14 min"
    static String duration(long seconds) {
        if (seconds < 60) return seconds + " s";
        if (seconds < 3600) return seconds / 60 + " min";
        return seconds / 3600 + " h " + seconds % 3600 / 60 + " min";
    }

    // what one problem comes to. heading: the line for the console above it, if it is judged
    CrawlIndex.Entry look(int id, Site site, Analyzer analyzer, CrawlIndex.Entry before, String heading) throws Exception {
        CrawlIndex.Entry e = entry(id, before);
        JsonObject p;
        try {
            p = site.problem(id);
        } catch (Exception ex) {
            return error(e, "reading it from the site: " + ex.getMessage());
        }
        if (ruledOut(e, p) != null) return e;
        String sgf = p.get("sgf").getAsString();
        String name = "gp-" + id;
        e.sgfHash = hash(sgf);
        e.difficulty = difficulty(p);
        Files.writeString(new File(dir, "sgf/" + name + ".sgf").toPath(), sgf + "\n");
        // an earlier judging's findings, draft and review are for a problem or a judging that
        // is gone, whatever this one comes to: left, a redraft would bring them back
        File findingsFile = new File(dir, "findings/" + name + ".json");
        Files.deleteIfExists(findingsFile.toPath());
        Files.deleteIfExists(new File(dir, "suggestions/" + name + ".json").toPath());
        retire(name);
        long t0 = System.currentTimeMillis();
        MissingVariationFinder.Outcome o = analyzer.analyze(name, sgf, e.difficulty, heading);
        e.seconds = (System.currentTimeMillis() - t0) / 1000;
        e.positions = o.positions;
        e.result = o.result;
        if ("error".equals(o.status)) return error(e, o.reason);
        e.version = MissingVariationFinder.JUDGING_VERSION;
        if ("skipped".equals(o.status)) return status(e, "skipped", o.reason);
        List<MissingVariationFinder.Finding> findings = !o.findings.isEmpty() ? o.findings
                : o.finding != null ? List.of(o.finding) : List.of();
        if (!findings.isEmpty()) Files.writeString(findingsFile.toPath(), RunRecorder.GSON.toJson(findings) + "\n");
        return draftFrom(e, sgf, findings);
    }

    // an entry for the problem as it is looked at now, with the errors counted before it
    private static CrawlIndex.Entry entry(int id, CrawlIndex.Entry before) {
        CrawlIndex.Entry e = new CrawlIndex.Entry();
        e.id = id;
        e.time = LocalDateTime.now().toString();
        e.attempts = before != null && "error".equals(before.status) ? before.attempts : 0;
        return e;
    }

    // what a problem comes to that the site's own record keeps from being judged (p, null when
    // the site has none at the id): skipped for good, or deferred while the site is not ready
    // with it. null when it is to be judged
    private CrawlIndex.Entry ruledOut(CrawlIndex.Entry e, JsonObject p) {
        if (p == null) return status(e, "skipped", "no such problem");
        String why = unsuitable(p, includeSandbox);
        if (CrawlIndex.NOT_STANDARD.equals(why)) {
            // a new problem is not standard until the site's editors mark it, so it is waited
            // for. one still unmarked long after it was made is taken to stay so (gp-62456, in
            // the site's Instruction Required collection, at eleven weeks). a problem the site
            // gives no date for is waited for: a wait costs a read a run, a skip loses it
            Long days = daysOld(p);
            if (waitDays > 0 && (days == null || days < waitDays)) return status(e, "deferred", CrawlIndex.NOT_STANDARD_YET);
            return status(e, "skipped", CrawlIndex.NOT_STANDARD + (waitDays > 0 ? " in its first " + waitDays + " days" : ", and none is waited for"));
        }
        if (why != null) return status(e, "skipped", why);
        int open = number(p, "suggestionsOpenCount");
        if (open > 0) return status(e, "deferred", open + " suggestion" + (open == 1 ? "" : "s") + " open on the site");
        return null;
    }

    // how many days ago the problem was made, as the site has it ("2026-09-27T02:55:05+00:00");
    // null when it does not say
    private Long daysOld(JsonObject p) {
        try {
            String made = text(p, "createdAt");
            return made == null ? null : (clock.getAsLong() - OffsetDateTime.parse(made).toInstant().toEpochMilli()) / 86_400_000L;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    // the suggestion the findings make, written to suggestions/: found, or unsuggested when
    // the finder found correct moves missing but none could go into a suggestion (a line that
    // ends on the problem's own wrong line, say), or clean. never fails for the problem: a
    // judging costs minutes, and the findings stay in findings/ to draft again from
    // (cmd=crawl-redraft)
    CrawlIndex.Entry draftFrom(CrawlIndex.Entry e, String sgf, List<MissingVariationFinder.Finding> findings) throws IOException {
        File draftFile = new File(dir, "suggestions/gp-" + e.id + ".json");
        boolean missing = findings.stream().anyMatch(f -> f.moves != null && !f.moves.isEmpty());
        List<String> notes = new ArrayList<>();
        SuggestionDraft draft = null;
        if (missing) {
            try {
                draft = SuggestionDraft.build(e.id, sgf, findings, e.version == null ? MissingVariationFinder.JUDGING_VERSION : e.version, notes);
            } catch (RuntimeException ex) {
                notes.add(String.valueOf(ex.getMessage()));
            }
        }
        if (draft == null) {
            Files.deleteIfExists(draftFile.toPath());
            if (missing) return status(e, "unsuggested", "correct moves missing, but none could go into a suggestion: " + String.join("; ", notes));
            List<String> short_ = new ArrayList<>();
            for (MissingVariationFinder.Finding f : findings) for (MissingVariationFinder.WrongMove w : f.wrong) short_.add(w.move);
            return status(e, "clean", short_.isEmpty() ? null : "accepted moves that fall short, not suggested: " + String.join(", ", short_));
        }
        RunRecorder.writeAtomically(draftFile, RunRecorder.GSON.toJson(draft));
        List<String> moves = new ArrayList<>();
        int cut = 0;
        for (SuggestionDraft.Place pl : draft.places) {
            moves.addAll(draft.points(pl.moves));
            cut += pl.cut.size();
        }
        return status(e, "found", String.join(", ", moves) + " missing (" + draft.places.size() + " position"
                + (draft.places.size() == 1 ? "" : "s") + ")" + (cut == 0 ? "" : "; " + cut + " line" + (cut == 1 ? "" : "s") + " cut short at a limit")
                + (notes.isEmpty() ? "" : "; left out: " + String.join("; ", notes)));
    }

    // cmd=crawl-redraft: the suggestions made again from the findings already judged, with no
    // engine, for when the drafting changes. a problem whose suggestion went to the site is left
    // alone; a review of a draft that changed is kept aside
    public void redraft() throws Exception {
        CrawlIndex index = CrawlIndex.load(new File(dir, "problems.jsonl"));
        Set<Integer> submitted = submittedIds();
        String[] names = new File(dir, "findings").list();
        if (names == null) names = new String[0];
        Arrays.sort(names);
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<MissingVariationFinder.Finding>>() {}.getType();
        for (String n : names) {
            if (!n.matches("gp-\\d+\\.json")) continue;
            int id = Integer.parseInt(n.substring(3, n.length() - 5));
            File sgfFile = new File(dir, "sgf/gp-" + id + ".sgf");
            if (submitted.contains(id) || !sgfFile.exists()) continue;
            String sgf = Files.readString(sgfFile.toPath());
            if (sgf.endsWith("\n")) sgf = sgf.substring(0, sgf.length() - 1);
            List<MissingVariationFinder.Finding> findings = RunRecorder.GSON.fromJson(Files.readString(new File(dir, "findings/" + n).toPath()), type);
            CrawlIndex.Entry before = index.get(id);
            CrawlIndex.Entry e = new CrawlIndex.Entry();
            e.id = id;
            e.version = before != null && before.version != null ? before.version : MissingVariationFinder.JUDGING_VERSION;
            e.difficulty = before != null ? before.difficulty : findings.isEmpty() ? null : findings.get(0).difficulty;
            e.sgfHash = hash(sgf);
            e.positions = before != null ? before.positions : null;
            e.seconds = before != null ? before.seconds : null;
            e.time = LocalDateTime.now().toString();
            File draftFile = new File(dir, "suggestions/gp-" + id + ".json");
            String old = draftFile.exists() ? Files.readString(draftFile.toPath()) : null;
            e = draftFrom(e, sgf, findings);
            String now = draftFile.exists() ? Files.readString(draftFile.toPath()) : null;
            if (old != null && (now == null || !sameSuggestion(old, now))) retire("gp-" + id);
            index.append(e);
            System.out.println("gp-" + id + ": " + e.status + (e.reason != null ? " (" + e.reason + ")" : ""));
        }
    }

    // whether two drafts suggest the same thing: the same sgf and comment
    private static boolean sameSuggestion(String a, String b) {
        SuggestionDraft x = RunRecorder.GSON.fromJson(a, SuggestionDraft.class), y = RunRecorder.GSON.fromJson(b, SuggestionDraft.class);
        return x.suggestedSgf.equals(y.suggestedSgf) && x.comment.equals(y.comment);
    }

    // the problems whose suggestions went to the site (submitted/gp-<id>.json)
    private Set<Integer> submittedIds() {
        Set<Integer> out = new java.util.HashSet<>();
        String[] names = new File(dir, "submitted").list();
        if (names == null) return out;
        for (String n : names) {
            if (n.matches("gp-\\d+\\.json")) out.add(Integer.parseInt(n.substring(3, n.length() - 5)));
        }
        return out;
    }

    // a review of an earlier draft for the problem no longer holds: it is kept aside, with any
    // edits made to the draft by hand
    private void retire(String name) throws IOException {
        File approval = new File(dir, "approvals/" + name + ".json");
        if (approval.exists()) Files.move(approval.toPath(), new File(dir, "approvals/" + name + ".old.json").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        File edit = ReviewEdit.file(dir, name);
        if (edit.exists()) Files.move(edit.toPath(), new File(dir, "edits/" + name + ".old.json").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // why a problem from the site is not judged, or null when it is
    static String unsuitable(JsonObject p, boolean includeSandbox) {
        String genre = text(p, "genre") != null ? text(p, "genre") : text(p, "specificGenre");
        if (!"life and death".equalsIgnoreCase(genre)) return "genre " + genre;
        if (!flag(p, "isStandard")) return CrawlIndex.NOT_STANDARD;
        if (p.has("alive") && !flag(p, "alive")) return "not alive on the site";
        if (!includeSandbox && flag(p, "sandbox")) return "in the sandbox";
        String sgf = text(p, "sgf");
        if (sgf == null || sgf.isBlank()) return "no sgf";
        return null;
    }

    // the problem's difficulty as the site ranks it, "18 kyu"
    static String difficulty(JsonObject p) {
        JsonElement r = p.get("rank");
        if (r == null || !r.isJsonObject()) return null;
        JsonObject rank = r.getAsJsonObject();
        if (!rank.has("value") || !rank.has("unit") || rank.get("value").isJsonNull()) return null;
        return rank.get("value").getAsInt() + " " + rank.get("unit").getAsString();
    }

    private CrawlIndex.Entry error(CrawlIndex.Entry e, String reason) {
        e.attempts++;
        return status(e, "error", reason);
    }

    private static CrawlIndex.Entry status(CrawlIndex.Entry e, String status, String reason) {
        e.status = status;
        e.reason = reason;
        return e;
    }

    private State readState() throws IOException {
        File f = new File(dir, "state.json");
        return f.exists() ? RunRecorder.GSON.fromJson(Files.readString(f.toPath()), State.class) : new State();
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static boolean flag(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    private static int number(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? 0 : e.getAsInt();
    }

    static String hash(String sgf) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(sgf.getBytes(StandardCharsets.UTF_8)));
    }
}
