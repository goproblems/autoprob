package autoprob.validate;

import java.awt.Point;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import autoprob.KataBrain;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.parse.Parser;
import autoprob.go.parse.SgfEdit;
import autoprob.go.parse.SmallBoard;
import autoprob.katastruct.KataAnalysisResult;

// cmd=line-ends: where review ended the lines the crawl suggested, against what the
// generator's rule saw there. for every suggestion review approved (crawl/approvals), each
// position after a solver move on its lines gets the opponent's decision measured as the
// generator measures it, and a label from the tree as it went to the site: END where the
// line ends there (as the checker had it, or cut back to there by hand in review), CONT
// where it goes on. the decisions go to study.out as json lines, so a rule for ending lines
// can be fitted to the reviews offline (ChoiceRule takes no engine).
//
// study.only=1,2 takes just those problems, study.shard=0/2 every second one (to run two
// engines side by side), study.max_nodes the most positions measured in one problem (the
// ends first, then the positions before them), study.edited=true only suggestions edited
// by hand, whose ends were chosen rather than left as they were
public class LineEndStudy {
    private final Properties props;
    private final File dir;
    private final File out;
    private final int maxNodes;

    // a position on a suggested line, after a solver move
    record Spot(List<String> at, List<String> line, String label, boolean checkerContinued) {
    }

    public LineEndStudy(Properties props) {
        this.props = props;
        this.dir = new File(props.getProperty("crawl.dir", "crawl"));
        this.out = new File(props.getProperty("study.out", new File(dir, "line_ends.jsonl").getPath()));
        this.maxNodes = Integer.parseInt(props.getProperty("study.max_nodes", "14"));
    }

    public void run() throws Exception {
        List<Integer> only = Arrays.stream(props.getProperty("study.only", "").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt).toList();
        String[] shard = props.getProperty("study.shard", "0/1").split("/");
        int part = Integer.parseInt(shard[0]), parts = Integer.parseInt(shard[1]);
        boolean editedOnly = Boolean.parseBoolean(props.getProperty("study.edited", "false"));
        String[] names = new File(dir, "approvals").list();
        if (names == null) names = new String[0];
        Arrays.sort(names);
        List<Integer> ids = new ArrayList<>();
        for (String n : names) {
            if (!n.matches("gp-\\d+\\.json")) continue;
            int id = Integer.parseInt(n.substring(3, n.length() - 5));
            if (!only.isEmpty() && !only.contains(id)) continue;
            JsonObject approval = RunRecorder.GSON.fromJson(Files.readString(new File(dir, "approvals/" + n).toPath()), JsonObject.class);
            if (!"approved".equals(approval.get("decision").getAsString())) continue;
            if (!new File(dir, "suggestions/" + n).exists()) continue;
            if (editedOnly && !ReviewEdit.file(dir, "gp-" + id).exists()) continue;
            ids.add(id);
        }
        Set<String> done = done();
        MissingVariationFinder finder = new MissingVariationFinder(props);
        KataBrain brain = new KataBrain(props);
        try {
            for (int i = 0; i < ids.size(); i++) {
                if (i % parts != part) continue;
                String name = "gp-" + ids.get(i);
                if (done.contains(name)) continue;
                try {
                    study(finder, brain, name);
                } catch (Exception e) {
                    e.printStackTrace();
                    System.out.println(name + ": failed (" + e.getMessage() + ")");
                    if (!brain.isAlive()) {
                        brain.stopKataBrain();
                        brain = new KataBrain(props);
                    }
                }
            }
        } finally {
            brain.stopKataBrain();
        }
    }

    // the problems the output has already
    private Set<String> done() throws Exception {
        if (!out.exists()) return Set.of();
        return Files.readAllLines(out.toPath()).stream().filter(l -> !l.isBlank())
                .map(l -> new Gson().fromJson(l, JsonObject.class).get("problem").getAsString()).collect(Collectors.toSet());
    }

    private void study(MissingVariationFinder finder, KataBrain brain, String name) throws Exception {
        SuggestionDraft draft = RunRecorder.GSON.fromJson(Files.readString(new File(dir, "suggestions/" + name + ".json").toPath()),
                SuggestionDraft.class);
        ReviewEdit edit = ReviewEdit.read(dir, name);
        boolean edited = edit != null && edit.holdsFor(draft);
        List<Spot> spots = spots(draft, edited ? edit.sgf : draft.suggestedSgf);
        System.out.println();
        System.out.println("=== " + name + ": " + spots.size() + " positions" + (edited ? ", edited in review" : ""));
        if (spots.isEmpty()) return;

        SmallBoard.Embedded embedded = SmallBoard.toNineteen(draft.originalSgf);
        Node root = new Parser().parse(embedded.sgf());
        MissingVariationFinder.Judging judging = finder.judging(brain, root, MovePath.extractRightPaths(root));
        ProblemIsolator.IsolatedProblem iso = judging.iso();
        Properties lineProps = finder.lineProps(iso);
        ChoiceParams params = ChoiceParams.fromProps(lineProps);
        // the generator's own view of each position, by where its lines start
        Map<List<String>, ProblemIsolator.IsolatedProblem> froms = new LinkedHashMap<>();
        Map<List<String>, DecisionFeatureExtractor> extractors = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        for (Spot s : spots) {
            ProblemIsolator.IsolatedProblem from = froms.get(s.at());
            if (from == null) {
                from = MissingVariationFinder.lineIsolation(iso, Positions.play(iso, s.at()), List.of());
                froms.put(s.at(), from);
                extractors.put(s.at(), new DecisionFeatureExtractor(lineProps, brain, from, params));
            }
            DecisionFeatureExtractor extractor = extractors.get(s.at());
            Node node;
            try {
                node = Positions.play(from, s.line());
            } catch (IllegalArgumentException e) {
                System.out.println("  cannot replay " + String.join(" ", s.line()) + ": " + e.getMessage());
                continue;
            }
            Node first = node;
            while (first.mom != null && first.mom != from.problem) first = first.mom;
            try {
                KataAnalysisResult after = extractor.after(node);
                if (after.isError()) continue;
                DecisionRecord rec = extractor.opponent(node, s.line(), after);
                if (rec.error != null) continue;
                ChoiceRule.Choice choice = ChoiceRule.chooseOpponent(rec, params, from.stakes);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("problem", name);
                row.put("edited", edited);
                row.put("at", s.at());
                row.put("line", s.line());
                row.put("label", s.label());
                row.put("checkerContinued", s.checkerContinued());
                // the stones at stake as the finder's judge reads them there, on the full isolation
                Node full = Positions.play(iso, plus(s.at(), s.line()));
                Node fullFirst = full;
                while (fullFirst.mom != null && fullFirst.mom != iso.problem) fullFirst = fullFirst.mom;
                try {
                    row.put("status", judging.judge().status(full, after).name());
                    row.put("margin", judging.judge().margin(full, after));
                } finally {
                    iso.problem.removeChildNode(fullFirst);
                }
                row.put("lead", (iso.solverColor == Intersection.BLACK ? 1 : -1) * after.blackScore());
                row.put("stakes", from.stakes.size());
                row.put("choice", choice.move);
                row.put("reason", choice.reason);
                row.put("rec", rec);
                sb.append(new Gson().toJson(row)).append("\n");
                System.out.println("  " + s.label() + " after " + String.join(" ", s.line()) + ": the rule " + (choice.isEnd() ? "ends" : "goes on with "
                        + choice.move) + " (" + choice.reason + ")");
            } finally {
                from.problem.removeChildNode(first);
            }
        }
        Files.writeString(out.toPath(), sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // the positions after a solver move on the draft's lines, with what the tree that went to
    // review's end (sgf) makes of each: the ends first, then the positions on the way to them
    List<Spot> spots(SuggestionDraft draft, String sgf) {
        SmallBoard.Embedded board = new SmallBoard.Embedded(draft.originalSgf, draft.boardSize, draft.dx, draft.dy);
        SgfEdit was = SgfEdit.parse(draft.originalSgf), checker = SgfEdit.parse(draft.suggestedSgf), now = SgfEdit.parse(sgf);
        Map<List<String>, Spot> byPath = new LinkedHashMap<>();
        for (SuggestionDraft.Place p : draft.places) {
            for (List<String> line : p.lines) {
                for (int i = 0; i < line.size(); i += 2) {
                    List<String> path = new ArrayList<>(p.at);
                    path.addAll(line.subList(0, i + 1));
                    if (byPath.containsKey(path)) continue;
                    // on the problem's own tree the opponent plays the problem's answers: no decision
                    SgfEdit.SgfNode own = was.at(path);
                    if (own != null && !own.children().isEmpty()) continue;
                    SgfEdit.SgfNode n = now.at(path), c = checker.at(path);
                    if (n == null || c == null) continue; // cut away in review
                    String label = n.children().isEmpty() ? (n.right() ? "END" : null) : "CONT";
                    if (label == null) continue;
                    byPath.put(path, new Spot(gtp(p.at, board), gtp(line.subList(0, i + 1), board), label, !c.children().isEmpty()));
                }
            }
        }
        List<Spot> all = new ArrayList<>(byPath.values());
        List<Spot> picked = new ArrayList<>();
        for (Spot s : all) if (s.label().equals("END") && picked.size() < maxNodes) picked.add(s);
        // then the positions the lines go on from, the ones nearest an end first
        all.sort((a, b) -> Integer.compare(b.line().size(), a.line().size()));
        for (Spot s : all) if (s.label().equals("CONT") && picked.size() < maxNodes) picked.add(s);
        return picked;
    }

    // sgf moves on the problem's own board ("B ka") as the finder plays them on 19x19 ("B R19")
    static List<String> gtp(List<String> moves, SmallBoard.Embedded board) {
        List<String> out = new ArrayList<>();
        for (String m : moves) {
            Point p = new Point(m.charAt(2) - 'a' + board.dx(), m.charAt(3) - 'a' + board.dy());
            out.add(m.charAt(0) + " " + Intersection.toGTPloc(p.x, p.y, 19));
        }
        return out;
    }

    private static List<String> plus(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
