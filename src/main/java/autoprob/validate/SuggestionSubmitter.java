package autoprob.validate;

import java.awt.Point;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import autoprob.ApiClient;
import autoprob.go.Intersection;
import autoprob.go.parse.SgfEdit;
import autoprob.go.parse.SmallBoard;

// cmd=suggest: offers a finding of the missing-variations finder (finding=<its json>) to
// goproblems.com as a suggested edit of the problem, which the author or an editor approves
// or rejects. a dry run unless suggest.post=true: it prints what it would send.
//
// the site (gp2, SuggestedProblemEditsController) takes problemId, comment, originalSgf and
// suggestedSgf, and checks none of them: approving a suggestion replaces the problem's sgf
// with the suggested one, and creating one notifies the problem's author and goes out on the
// site's public feed. so the checks are made here:
// - the suggestion starts from the sgf the site has now, and adds the finding's lines to it
//   on the problem's own board and nothing else, since the site shows reviewers every node
//   that changed;
// - the problem must not have changed since the finder looked at it (suggest.source, the
//   file it read, sample_problems/<problem>.sgf by default);
// - no suggestion is open on the problem, and none made before has the lines already,
//   approved or rejected (suggest.force=true goes on past an open one)
public class SuggestionSubmitter {
    private static final String COLUMNS = "ABCDEFGHJKLMNOPQRST";

    private final Properties props;
    private final ApiClient api = new ApiClient();

    public SuggestionSubmitter(Properties props) {
        this.props = new Properties();
        this.props.putAll(props);
        // the site's routes (gp2): the list takes criteria[...] in its query
        this.props.putIfAbsent("api.problem.details", "api/v2/problems/{id}");
        this.props.putIfAbsent("api.suggestions", "api/v2/suggested_problem_edits/");
        this.props.putIfAbsent("api.suggestion", "api/v2/suggested_problem_edits/{id}");
        this.props.putIfAbsent("api.suggestion.create", "api/v2/suggested_problem_edits");
    }

    public void run() throws Exception {
        String path = props.getProperty("finding");
        if (path == null) throw new IllegalArgumentException("finding=<a *_missing.json the finder wrote> is required");
        boolean post = Boolean.parseBoolean(props.getProperty("suggest.post", "false"));
        boolean force = Boolean.parseBoolean(props.getProperty("suggest.force", "false"));
        MissingVariationFinder.Finding f = new Gson().fromJson(Files.readString(new File(path).toPath()),
                MissingVariationFinder.Finding.class);
        if (f.moves == null || f.moves.isEmpty()) {
            System.out.println(f.problem + ": the finding has no missing move; moves that fall short are not suggested yet");
            return;
        }
        int id = Integer.parseInt(f.problem.replaceFirst("^gp-", ""));

        // the problem as the site has it now
        JsonObject problem = get("api.problem.details", Map.of("id", String.valueOf(id)), null);
        String original = problem.get("sgf").getAsString();
        File source = new File(props.getProperty("suggest.source", "sample_problems/" + f.problem + ".sgf"));
        if (source.exists() && !sameProblem(original, Files.readString(source.toPath()))) {
            System.out.println(f.problem + ": the problem on the site is not the one the finder read (" + source
                    + "); run the finder on it again");
            return;
        }

        // the lines on the problem's own board
        SmallBoard.Embedded board = SmallBoard.toNineteen(original);
        List<String> at = onBoard(f.at, board);
        List<List<String>> lines = new ArrayList<>();
        for (MissingVariationFinder.FoundMove m : f.moves) {
            List<List<String>> ls = m.lines == null || m.lines.isEmpty() ? List.of(List.of(m.move)) : m.lines;
            for (List<String> l : ls) lines.add(onBoard(l, board));
        }

        // the suggestions the problem has had
        JsonArray entries = get("api.suggestions", null,
                "?criteria%5BproblemId%5D=" + id + "&criteria%5Blimit%5D=100").getAsJsonArray("entries");
        for (JsonElement e : entries) {
            JsonObject s = e.getAsJsonObject();
            String sid = s.get("id").getAsString();
            String status = s.has("status") && !s.get("status").isJsonNull() ? s.get("status").getAsString() : "?";
            if (status.equals("open") && !force) {
                System.out.println(f.problem + ": suggestion #" + sid + " is open on the problem; approving either would undo"
                        + " the other, so this waits for it (suggest.force=true goes on)");
                return;
            }
            JsonObject full = get("api.suggestion", Map.of("id", sid), null);
            JsonElement sgf = full.get("suggestedSgf");
            if (sgf == null || sgf.isJsonNull()) continue;
            SgfEdit theirs = SgfEdit.parse(sgf.getAsString());
            for (List<String> l : lines) {
                if (theirs.has(at, l)) {
                    System.out.println(f.problem + ": suggestion #" + sid + " (" + status + ") has the line "
                            + String.join(" ", points(l, board)) + " already");
                    return;
                }
            }
        }

        // the problem with the lines added, and nothing else
        SgfEdit edit = SgfEdit.parse(original);
        if (!SgfEdit.parse(edit.write()).write().equals(edit.write())) throw new IllegalStateException("the sgf does not read back the same");
        int added = 0;
        for (List<String> l : lines) {
            if (edit.has(at, l)) continue;
            added += edit.addLine(at, l);
        }
        if (added == 0) {
            System.out.println(f.problem + ": the problem has every line already");
            return;
        }
        String suggested = edit.write();
        String comment = props.getProperty("suggest.comment", comment(f, at, lines, board));

        JsonObject body = new JsonObject();
        body.addProperty("problemId", id);
        body.addProperty("comment", comment);
        body.addProperty("originalSgf", original);
        body.addProperty("suggestedSgf", suggested);
        System.out.println(f.problem + ": " + entries.size() + " suggestion" + (entries.size() == 1 ? "" : "s") + " before, "
                + added + " node" + (added == 1 ? "" : "s") + " added");
        System.out.println("comment: " + comment);
        System.out.println("original:  " + original);
        System.out.println("suggested: " + suggested);
        if (!post) {
            System.out.println("dry run: suggest.post=true sends it");
            return;
        }
        ApiClient.ApiResponse<JsonObject> r = api.makePostRequest("api.suggestion.create", null, body.toString(), JsonObject.class, props);
        if (!r.isSuccess()) throw new IllegalStateException("the site refused the suggestion: http " + r.getStatusCode() + " " + r.getErrorMessage());
        System.out.println("suggested: #" + r.getData().get("id").getAsString() + " on problem " + id);
    }

    private JsonObject get(String endpoint, Map<String, String> params, String query) throws Exception {
        ApiClient.ApiResponse<JsonObject> r = api.makeGetRequest(endpoint, params, query, JsonObject.class, props);
        if (!r.isSuccess()) throw new IllegalStateException(endpoint + ": http " + r.getStatusCode() + " " + r.getErrorMessage());
        return r.getData();
    }

    // whether two sgfs are the same problem: the same tree, node for node, but for the
    // difficulty our downloads add to the root
    static boolean sameProblem(String site, String local) {
        return SgfEdit.parse(site).write().equals(SgfEdit.parse(local.replaceFirst("DI\\[[^\\]]*\\]", "")).write());
    }

    // moves the finder played on 19x19 ("B R19"), as sgf moves on the problem's own board ("B ka")
    static List<String> onBoard(List<String> moves, SmallBoard.Embedded board) {
        List<String> out = new ArrayList<>();
        for (String m : moves) {
            Point p = Intersection.gtp2point(m.substring(2));
            int x = p.x - board.dx(), y = p.y - board.dy();
            if (x < 0 || y < 0 || x >= board.size() || y >= board.size()) {
                throw new IllegalArgumentException(m + " is off the problem's " + board.size() + "x" + board.size() + " board");
            }
            out.add(m.charAt(0) + " " + (char) ('a' + x) + (char) ('a' + y));
        }
        return out;
    }

    // sgf moves as a player reads them on the problem's board ("B L13")
    static List<String> points(List<String> moves, SmallBoard.Embedded board) {
        List<String> out = new ArrayList<>();
        for (String m : moves) {
            int x = m.charAt(2) - 'a', y = m.charAt(3) - 'a';
            out.add(m.charAt(0) + " " + COLUMNS.charAt(x) + (board.size() - y));
        }
        return out;
    }

    // what the suggestion says, for whoever reviews it, and in the problem's history once it is
    // approved: the position, the move and the line added, and how far to trust it
    static String comment(MissingVariationFinder.Finding f, List<String> at, List<List<String>> lines,
                          SmallBoard.Embedded board) {
        List<String> moves = new ArrayList<>();
        for (MissingVariationFinder.FoundMove m : f.moves) moves.add(points(onBoard(List.of(m.move), board), board).get(0).substring(2));
        List<String> accepted = new ArrayList<>();
        if (f.accepted != null) for (String a : f.accepted) accepted.add(points(onBoard(List.of(a), board), board).get(0).substring(2));
        String side = f.moves.get(0).move.startsWith("B") ? "Black" : "White";
        StringBuilder sb = new StringBuilder();
        sb.append(at.isEmpty() ? "At the start" : "After " + String.join(", ", points(at, board)));
        sb.append(", ").append(side).append("'s ").append(String.join(" and ", moves))
                .append(moves.size() == 1 ? " works too" : " work too");
        if (!accepted.isEmpty()) sb.append(", with the same result as ").append(String.join(" and ", accepted));
        sb.append(". Line").append(lines.size() == 1 ? "" : "s").append(" added: ");
        List<String> shown = new ArrayList<>();
        for (List<String> l : lines) shown.add(String.join(", ", points(l, board)));
        sb.append(String.join("; ", shown)).append(".");
        Confidence c = f.moves.get(0).confidence;
        sb.append(" Found by autoprob, an automated checker");
        if (c != null) sb.append(" (confidence: ").append(c.label).append(", ").append(c.low).append("-").append(c.high).append("%)");
        return sb.append(".").toString();
    }
}
