package autoprob.validate;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

// cmd=crawl-submit: sends the crawl's suggestions approved in its review (cmd=crawl-review) to
// goproblems.com, each after the checks cmd=suggest makes against the site as it is now: the
// problem's sgf is still the one the suggestion was made from, no suggestion is open on it, and
// none made before has its lines. a dry run unless crawl.post=true; crawl.only=gp-1,gp-2 limits
// it to those. a problem whose sgf changed on the site is marked stale, its review kept aside
// (approvals/gp-<id>.stale.json), and the next crawl judges it again. a suggestion edited by
// hand in review (ReviewEdit) goes as edited.
//
// cmd=crawl-sync reads back what became of the sent ones: open, approved or rejected, and what
// the reviewer said.
public class CrawlSubmitter {
    // a review's decision on a suggestion (approvals/gp-<id>.json)
    public static class Approval {
        public String decision; // approved or rejected
        public String comment;  // the comment as the review left it
        public String note;     // the reviewer's own note, not sent
        public String by;
        public String time;
    }

    // a suggestion sent to the site (submitted/gp-<id>.json)
    public static class Submitted {
        public int suggestionId;
        public int problemId;
        public String comment;
        public String sent;
        public String status;            // open, approved or rejected, as last synced
        public String resolutionComment;
        public String resolvedBy;
        public String resolvedAt;
        public String synced;
    }

    private final Properties props;
    private final File dir;

    public CrawlSubmitter(Properties props) {
        this.props = props;
        this.dir = new File(props.getProperty("crawl.dir", "crawl"));
    }

    public void submit() throws Exception {
        boolean post = Boolean.parseBoolean(props.getProperty("crawl.post", "false"));
        Set<String> only = Arrays.stream(props.getProperty("crawl.only", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        GoproblemsSite site = new GoproblemsSite(props);
        CrawlIndex index = CrawlIndex.load(new File(dir, "problems.jsonl"));
        new File(dir, "submitted").mkdirs();
        Map<String, Integer> counts = new TreeMap<>();
        for (String name : approved()) {
            if (!only.isEmpty() && !only.contains(name)) continue;
            String outcome = submitOne(name, site, index, post);
            counts.merge(outcome, 1, Integer::sum);
        }
        System.out.println(counts.isEmpty() ? "no approved suggestion waiting to be sent" : "done: " + counts);
        if (!post && counts.containsKey("ready")) System.out.println("dry run: crawl.post=true sends them");
    }

    // what came of one approved suggestion: sent, ready (a dry run), stale, blocked or gone
    private String submitOne(String name, GoproblemsSite site, CrawlIndex index, boolean post) throws Exception {
        File draftFile = new File(dir, "suggestions/" + name + ".json");
        if (!draftFile.exists()) {
            System.out.println(name + ": approved, but its suggestion is gone; judge it again");
            return "gone";
        }
        SuggestionDraft asMade = RunRecorder.GSON.fromJson(Files.readString(draftFile.toPath()), SuggestionDraft.class);
        // edited by hand in review, it goes as edited
        SuggestionDraft draft = ReviewEdit.apply(ReviewEdit.read(dir, name), asMade);
        if (draft.addedNodes == 0 || draft.places.isEmpty()) {
            System.out.println(name + ": approved, but its edits leave nothing to add; reject it, or take it back to pending");
            return "empty";
        }
        Approval a = read(new File(dir, "approvals/" + name + ".json"), Approval.class);
        String comment = a.comment != null && !a.comment.isBlank() ? a.comment : draft.comment;
        JsonObject live = site.problem(draft.problemId);
        if (live == null) {
            System.out.println(name + ": the site has no such problem any more");
            return "gone";
        }
        if (!draft.originalSgf.equals(live.get("sgf").getAsString())) {
            CrawlIndex.Entry e = new CrawlIndex.Entry();
            e.id = draft.problemId;
            e.status = "stale";
            e.reason = "changed on the site since its suggestion was made";
            e.time = LocalDateTime.now().toString();
            index.append(e);
            Files.move(new File(dir, "approvals/" + name + ".json").toPath(), new File(dir, "approvals/" + name + ".stale.json").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            System.out.println(name + ": changed on the site since its suggestion was made; the next crawl judges it again");
            return "stale";
        }
        String blocked = draft.blockedBy(site, false);
        if (blocked != null) {
            System.out.println(name + ": " + blocked + "; it waits");
            return "blocked";
        }
        System.out.println(name + ": " + draft.addedNodes + " node" + (draft.addedNodes == 1 ? "" : "s") + " added"
                + (draft != asMade ? " (edited in review)" : "") + "; " + comment);
        if (!post) return "ready";
        JsonObject made = site.create(draft.payload(comment));
        Submitted s = new Submitted();
        s.suggestionId = made.get("id").getAsInt();
        s.problemId = draft.problemId;
        s.comment = comment;
        s.sent = LocalDateTime.now().toString();
        s.status = text(made, "status");
        RunRecorder.writeAtomically(new File(dir, "submitted/" + name + ".json"), RunRecorder.GSON.toJson(s));
        System.out.println(name + ": sent as suggestion #" + s.suggestionId);
        return "sent";
    }

    // the approved suggestions not sent yet, newest problem first
    private List<String> approved() throws IOException {
        List<String> out = new ArrayList<>();
        String[] names = new File(dir, "approvals").list();
        if (names == null) return out;
        for (String n : names) {
            if (!n.matches("gp-\\d+\\.json")) continue;
            String name = n.substring(0, n.length() - 5);
            Approval a = read(new File(dir, "approvals/" + n), Approval.class);
            if (!"approved".equals(a.decision) || new File(dir, "submitted/" + n).exists()) continue;
            out.add(name);
        }
        out.sort((x, y) -> Integer.compare(Integer.parseInt(y.substring(3)), Integer.parseInt(x.substring(3))));
        return out;
    }

    public void sync() throws Exception {
        GoproblemsSite site = new GoproblemsSite(props);
        String[] names = new File(dir, "submitted").list();
        Map<String, Integer> counts = new TreeMap<>();
        if (names != null) {
            Arrays.sort(names);
            for (String n : names) {
                if (!n.matches("gp-\\d+\\.json")) continue;
                File f = new File(dir, "submitted/" + n);
                Submitted s = read(f, Submitted.class);
                JsonObject full = site.suggestion(s.suggestionId);
                String before = s.status;
                if (full == null) {
                    s.status = "deleted";
                } else {
                    s.status = text(full, "status");
                    s.resolutionComment = text(full, "resolutionComment");
                    s.resolvedAt = text(full, "resolvedAt");
                    JsonElement by = full.get("resolvedBy");
                    s.resolvedBy = by != null && by.isJsonObject() ? text(by.getAsJsonObject(), "name") : null;
                }
                s.synced = LocalDateTime.now().toString();
                RunRecorder.writeAtomically(f, RunRecorder.GSON.toJson(s));
                counts.merge(s.status, 1, Integer::sum);
                if (!s.status.equals(before) || !"open".equals(s.status)) {
                    System.out.println(n.substring(0, n.length() - 5) + ": #" + s.suggestionId + " " + s.status
                            + (s.resolvedBy != null ? " by " + s.resolvedBy : "")
                            + (s.resolutionComment != null && !s.resolutionComment.isBlank() ? ": " + s.resolutionComment : ""));
                }
            }
        }
        System.out.println(counts.isEmpty() ? "no suggestion sent yet" : "suggestions sent: " + counts);
    }

    private static <T> T read(File f, Class<T> type) throws IOException {
        return RunRecorder.GSON.fromJson(Files.readString(f.toPath()), type);
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
