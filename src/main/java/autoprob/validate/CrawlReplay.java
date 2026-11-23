package autoprob.validate;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import autoprob.go.parse.SgfEdit;

// cmd=crawl-replay: the problems review has judged, judged again as the finder is now, against
// what review made of them. it reads each problem as the crawl kept it (crawl/sgf), with no
// request to the site, and writes what it finds to replay.dir (crawl-replay/), never to the
// crawl's own folder: the crawl's suggestions and their reviews stay as they are. for a
// suggestion review approved, the tree as it went to the site (with the edits made by hand)
// is what the new one is held against, node for node; for one it rejected, nothing should be
// suggested any more. a change to how moves are judged or lines are drawn can be measured this
// way before the crawl runs on it.
//
// replay.only=1,2 takes just those problems; replay.set=approved, rejected or edited takes
// those reviews (default: all of them); replay.shard=0/2 every second one, to run two engines
// side by side. a problem replay.dir has already is not judged again. cmd=crawl-replay
// replay.report=true only prints the table from what is there
public class CrawlReplay {
    private final Properties props;
    private final File dir;
    private final File out;

    // what one replayed problem came to
    static class Row {
        int id;
        String decision;   // review's: approved or rejected
        String note;       // review's note
        boolean edited;    // review cut or added lines by hand
        String status;     // now: found, clean, skipped, unsuggested, unfinished or error
        String reason;
        String unfinished; // what the finder's time left undone, when it ran out (missing.max_minutes)
        long seconds;
        int reviewed;      // nodes review's tree adds to the problem (approved only)
        int was;           // nodes the crawl's draft added
        int now;           // nodes the new draft adds
        int kept;          // of review's nodes, the ones the new draft has
        int extra;         // nodes of the new draft review's tree lacks
        List<String> lost = new ArrayList<>();   // review's lines the new draft lacks
        List<String> added = new ArrayList<>();  // the new draft's lines review's tree lacks
    }

    public CrawlReplay(Properties props) {
        this.props = props;
        this.dir = new File(props.getProperty("crawl.dir", "crawl"));
        this.out = new File(props.getProperty("replay.dir", "crawl-replay"));
    }

    public void run() throws Exception {
        for (String d : List.of("findings", "suggestions")) new File(out, d).mkdirs();
        File results = new File(out, "results.jsonl");
        if (Boolean.parseBoolean(props.getProperty("replay.report", "false"))) {
            report(read(results));
            return;
        }
        List<Integer> only = Arrays.stream(props.getProperty("replay.only", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt).toList();
        String set = props.getProperty("replay.set", "all");
        String[] shard = props.getProperty("replay.shard", "0/1").split("/");
        int part = Integer.parseInt(shard[0]), parts = Integer.parseInt(shard[1]);
        String[] names = new File(dir, "approvals").list();
        if (names == null) names = new String[0];
        Arrays.sort(names);
        List<Integer> ids = new ArrayList<>();
        for (String n : names) {
            if (!n.matches("gp-\\d+\\.json")) continue;
            int id = Integer.parseInt(n.substring(3, n.length() - 5));
            if (!only.isEmpty() && !only.contains(id)) continue;
            if (!new File(dir, "suggestions/" + n).exists() || !new File(dir, "sgf/gp-" + id + ".sgf").exists()) continue;
            String decision = RunRecorder.GSON.fromJson(Files.readString(new File(dir, "approvals/" + n).toPath()), JsonObject.class)
                    .get("decision").getAsString();
            boolean edited = ReviewEdit.file(dir, "gp-" + id).exists();
            if (set.equals("approved") && !decision.equals("approved")) continue;
            if (set.equals("rejected") && !decision.equals("rejected")) continue;
            if (set.equals("edited") && !edited) continue;
            ids.add(id);
        }
        Set<Integer> done = read(results).stream().map(r -> r.id).collect(Collectors.toSet());
        Properties finderProps = new Properties();
        finderProps.putAll(props);
        finderProps.setProperty("missing.all_positions", "true");
        finderProps.setProperty("missing.dir", new File(out, "findings").getPath());
        MissingVariationFinder finder = new MissingVariationFinder(finderProps);
        finder.start();
        Thread hook = new Thread(finder::stop);
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            for (int i = 0; i < ids.size(); i++) {
                int id = ids.get(i);
                if (i % parts != part || done.contains(id)) continue;
                Row row = replay(finder, id);
                Files.writeString(results.toPath(), new Gson().toJson(row) + "\n", StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                System.out.println(line(row));
            }
        } finally {
            finder.stop();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // shutting down already
            }
        }
        report(read(results));
    }

    private Row replay(MissingVariationFinder finder, int id) throws Exception {
        String name = "gp-" + id;
        Row row = new Row();
        row.id = id;
        JsonObject approval = RunRecorder.GSON.fromJson(Files.readString(new File(dir, "approvals/" + name + ".json").toPath()), JsonObject.class);
        row.decision = approval.get("decision").getAsString();
        row.note = approval.has("note") ? approval.get("note").getAsString() : "";
        SuggestionDraft before = RunRecorder.GSON.fromJson(Files.readString(new File(dir, "suggestions/" + name + ".json").toPath()),
                SuggestionDraft.class);
        ReviewEdit edit = ReviewEdit.read(dir, name);
        row.edited = edit != null && edit.holdsFor(before);
        String sgf = Files.readString(new File(dir, "sgf/" + name + ".sgf").toPath());
        if (sgf.endsWith("\n")) sgf = sgf.substring(0, sgf.length() - 1);

        long t0 = System.currentTimeMillis();
        MissingVariationFinder.Outcome o = finder.check(name, sgf, before.difficulty, "replaying " + name + ", which review " + row.decision);
        row.seconds = (System.currentTimeMillis() - t0) / 1000;
        row.status = o.status;
        row.reason = o.reason;
        row.unfinished = o.unfinished;
        List<MissingVariationFinder.Finding> findings = !o.findings.isEmpty() ? o.findings
                : o.finding != null ? List.of(o.finding) : List.of();
        SuggestionDraft now = null;
        if (!"error".equals(o.status) && !"skipped".equals(o.status)) {
            if (!findings.isEmpty()) {
                Files.writeString(new File(out, "findings/" + name + ".json").toPath(), RunRecorder.GSON.toJson(findings) + "\n");
            }
            List<String> notes = new ArrayList<>();
            try {
                now = SuggestionDraft.build(id, sgf, findings, MissingVariationFinder.JUDGING_VERSION, notes);
                if (now != null) now.unfinished = o.unfinished;
            } catch (RuntimeException e) {
                notes.add(String.valueOf(e.getMessage()));
            }
            boolean missing = findings.stream().anyMatch(f -> f.moves != null && !f.moves.isEmpty());
            row.status = now != null ? "found" : missing ? "unsuggested" : o.unfinished != null ? "unfinished" : "clean";
            if (now == null && !notes.isEmpty()) row.reason = String.join("; ", notes);
            if (now != null) RunRecorder.writeAtomically(new File(out, "suggestions/" + name + ".json"), RunRecorder.GSON.toJson(now));
        }

        SgfEdit original = SgfEdit.parse(before.originalSgf);
        Set<String> wasLines = lines(SgfEdit.parse(before.suggestedSgf), original);
        Set<String> nowLines = now == null ? Set.of() : lines(SgfEdit.parse(now.suggestedSgf), original);
        row.was = nodes(wasLines);
        row.now = nodes(nowLines);
        if (row.decision.equals("approved")) {
            Set<String> reviewed = row.edited ? lines(SgfEdit.parse(edit.sgf), original) : wasLines;
            row.reviewed = nodes(reviewed);
            Set<String> reviewedNodes = prefixes(reviewed), nowNodes = prefixes(nowLines);
            row.kept = (int) reviewedNodes.stream().filter(nowNodes::contains).count();
            row.extra = (int) nowNodes.stream().filter(n -> !reviewedNodes.contains(n)).count();
            for (String l : reviewed) if (!nowLines.contains(l)) row.lost.add(l);
            for (String l : nowLines) if (!reviewed.contains(l)) row.added.add(l);
        } else {
            row.extra = row.now;
            row.added.addAll(nowLines);
        }
        return row;
    }

    // the lines a tree adds to the original: each path from the start to a node marked right
    // that goes through a node the original lacks, or ends on one it does not mark right, from
    // the first such node on ("after B ka W lb: B mc W md B me")
    static Set<String> lines(SgfEdit tree, SgfEdit original) {
        Set<String> out = new LinkedHashSet<>();
        walk(tree.root(), original.root(), new ArrayList<>(), -1, out);
        return out;
    }

    private static void walk(SgfEdit.SgfNode node, SgfEdit.SgfNode was, List<String> path, int first, Set<String> out) {
        for (SgfEdit.SgfNode c : node.children()) {
            SgfEdit.SgfNode w = was == null ? null : was.child(c.move());
            path.add(c.move());
            int f = first >= 0 ? first : w == null ? path.size() - 1 : -1;
            if (f >= 0 && c.right()) {
                out.add((f == 0 ? "" : "after " + String.join(" ", path.subList(0, f)) + ": ") + String.join(" ", path.subList(f, path.size())));
            }
            walk(c, w, path, f, out);
            path.remove(path.size() - 1);
        }
    }

    // every node the lines add, as the position and the moves to it
    static Set<String> prefixes(Set<String> lines) {
        Set<String> out = new LinkedHashSet<>();
        for (String l : lines) {
            int colon = l.indexOf(": ");
            String at = colon < 0 ? "" : l.substring(0, colon + 2);
            String[] moves = (colon < 0 ? l : l.substring(colon + 2)).split(" ");
            StringBuilder sb = new StringBuilder(at);
            for (int i = 0; i + 1 < moves.length; i += 2) {
                sb.append(moves[i]).append(' ').append(moves[i + 1]).append(' ');
                out.add(sb.toString());
            }
        }
        return out;
    }

    private static int nodes(Set<String> lines) {
        return prefixes(lines).size();
    }

    private static List<Row> read(File results) throws Exception {
        List<Row> rows = new ArrayList<>();
        if (!results.exists()) return rows;
        Map<Integer, Row> byId = new LinkedHashMap<>();
        for (String l : Files.readAllLines(results.toPath())) {
            if (l.isBlank()) continue;
            Row r = new Gson().fromJson(l, Row.class);
            byId.put(r.id, r);
        }
        rows.addAll(byId.values());
        return rows;
    }

    private static String line(Row r) {
        StringBuilder sb = new StringBuilder("gp-" + r.id + ": review " + r.decision + (r.edited ? " (edited)" : "")
                + (r.note == null || r.note.isBlank() ? "" : " \"" + r.note + "\"") + "; now " + r.status
                + (r.reason != null && !"found".equals(r.status) ? " (" + r.reason + ")" : "")
                + (r.unfinished != null ? " [" + r.unfinished + "]" : ""));
        if (r.decision.equals("approved")) {
            sb.append("; ").append(r.kept).append(" of review's ").append(r.reviewed).append(" nodes kept, ").append(r.extra)
                    .append(" more (the crawl's draft had ").append(r.was).append(")");
        } else {
            sb.append("; ").append(r.now).append(" nodes suggested (the crawl's draft had ").append(r.was).append(")");
        }
        return sb.toString();
    }

    // how the replayed problems came out against their reviews
    static void report(List<Row> rows) {
        System.out.println();
        System.out.println("=== " + rows.size() + " reviewed suggestions replayed");
        int approved = 0, same = 0, kept = 0, shorter = 0, longer = 0, lostAll = 0, reviewedNodes = 0, wasNodes = 0, nowNodes = 0, extraNodes = 0;
        int rejected = 0, dropped = 0, still = 0, stillNodes = 0, stillWas = 0;
        for (Row r : rows) {
            if (r.decision.equals("approved")) {
                approved++;
                reviewedNodes += r.reviewed;
                wasNodes += r.was;
                nowNodes += r.now;
                extraNodes += r.extra;
                if (r.kept == 0) lostAll++;
                else if (r.lost.isEmpty() && r.added.isEmpty()) same++;
                else if (r.kept == r.reviewed) longer++;
                else if (r.extra == 0) shorter++;
                else kept++;
            } else {
                rejected++;
                if (r.now == 0) dropped++;
                else {
                    still++;
                    stillNodes += r.now;
                    stillWas += r.was;
                }
            }
        }
        System.out.println("approved in review: " + approved);
        System.out.println("  the same tree as review's:        " + same);
        System.out.println("  all of review's nodes, and more:  " + longer);
        System.out.println("  part of review's nodes, no more:  " + shorter);
        System.out.println("  part of review's nodes, and more: " + kept);
        System.out.println("  none of review's nodes:           " + lostAll);
        System.out.println("  nodes: review's trees add " + reviewedNodes + ", the crawl's drafts added " + wasNodes + ", now " + nowNodes
                + " (" + extraNodes + " that review's trees lack)");
        System.out.println("rejected in review: " + rejected);
        System.out.println("  nothing suggested now:  " + dropped);
        System.out.println("  still suggested:        " + still + " (" + stillNodes + " nodes; the crawl's drafts had " + stillWas + ")");
        System.out.println();
        for (Row r : rows) System.out.println(line(r));
    }
}
