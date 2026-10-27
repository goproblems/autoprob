package autoprob.validate;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import autoprob.KataBrain;
import autoprob.go.Node;
import autoprob.go.parse.Parser;
import autoprob.go.parse.SmallBoard;

// cmd=levels: the levels moves reach, as reviewed, against what the missing-variations
// finder's classifier makes of them. the file (levels.file, cases/levels.txt) has a line per
// position, with lines starting with # saying why:
//   problem | moves from the start, or start | move LEVEL, move LEVEL, ...
// LEVEL is CLEAN, SEKI, KO, FAIL, !CLEAN (anything but clean) or ? (just report). problems
// are read from levels.problems (sample_problems), and problem=a,b checks only those
public class LevelChecker {
    static final Set<String> EXPECTED = Set.of("CLEAN", "SEKI", "KO", "FAIL", "!CLEAN", "?");

    record Judged(String move, String expected) {
    }

    record Entry(String problem, List<String> at, List<Judged> moves) {
    }

    private final Properties props;

    public LevelChecker(Properties props) {
        this.props = props;
    }

    public void run() throws Exception {
        File file = new File(props.getProperty("levels.file", "cases/levels.txt"));
        File dir = new File(props.getProperty("levels.problems", "sample_problems"));
        String only = props.getProperty("problem");
        Map<String, List<Entry>> byProblem = new LinkedHashMap<>();
        for (Entry e : parse(Files.readAllLines(file.toPath()))) {
            if (only != null && !Arrays.asList(only.split(",")).contains(e.problem())) continue;
            byProblem.computeIfAbsent(e.problem(), p -> new ArrayList<>()).add(e);
        }
        // a move the check cannot judge, for an error, counts against it
        int total = 0, agree = 0;
        for (List<Entry> entries : byProblem.values())
            for (Entry e : entries) total += (int) e.moves().stream().filter(j -> !j.expected().equals("?")).count();
        MissingVariationFinder finder = new MissingVariationFinder(props);
        KataBrain brain = new KataBrain(props);
        List<String> report = new ArrayList<>();
        try {
            for (Map.Entry<String, List<Entry>> p : byProblem.entrySet()) {
                try {
                    String sgf = Files.readString(new File(dir, p.getKey() + ".sgf").toPath());
                    Node root = new Parser().parse(SmallBoard.toNineteen(sgf).sgf());
                    MoveClassifier classifier = finder.judging(brain, root, MovePath.extractRightPaths(root)).quick();
                    for (Entry e : p.getValue()) {
                        String position = e.at().isEmpty() ? "start" : String.join(",", e.at());
                        if (position.length() > 28) position = "..." + position.substring(position.length() - 25);
                        for (Judged j : e.moves()) {
                            long t0 = System.currentTimeMillis();
                            MoveClassifier.Verdict v = classifier.classify(e.at(), j.move());
                            long secs = (System.currentTimeMillis() - t0) / 1000;
                            boolean ok = meets(j.expected(), v.level);
                            if (ok && !j.expected().equals("?")) agree++;
                            String out = String.format("%-9s %-28s %-6s expected %-6s got %-5s %s  clean %s ko %s%s  (%d s)",
                                    e.problem(), position, j.move(), j.expected(), v.level, ok ? "ok  " : "MISS", v.clean, v.ko,
                                    v.refutation != null ? ", answer " + v.refutation : "", secs);
                            System.out.println(out);
                            if (v.line != null) {
                                System.out.println("          line: "
                                        + String.join(" ", v.line.subList(Math.min(e.at().size(), v.line.size()), v.line.size())));
                            }
                            report.add(out);
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    report.add(String.format("%-9s error: %s", p.getKey(), ex.getMessage() == null ? ex : ex.getMessage()));
                    if (!brain.isAlive()) brain = restart(brain);
                }
            }
        } finally {
            brain.stopKataBrain();
        }
        System.out.println();
        System.out.println("=== " + agree + " of " + total + " as expected");
        for (String r : report) System.out.println(r);
    }

    // whether a level is what the line expects of the move
    static boolean meets(String expected, MoveClassifier.Level level) {
        return expected.equals("?") || expected.equals(level.name())
                || (expected.equals("!CLEAN") && level != MoveClassifier.Level.CLEAN);
    }

    static List<Entry> parse(List<String> lines) {
        List<Entry> out = new ArrayList<>();
        for (int n = 0; n < lines.size(); n++) {
            String line = lines.get(n).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|");
            if (parts.length != 3) throw new IllegalArgumentException("line " + (n + 1) + ": want problem | position | moves");
            String position = parts[1].trim();
            List<String> at = position.equals("start") ? List.of()
                    : Arrays.stream(position.split(",")).map(String::trim).toList();
            List<Judged> moves = new ArrayList<>();
            for (String item : parts[2].split(",")) {
                String[] mv = item.trim().split("\\s+");
                if (mv.length != 3 || !EXPECTED.contains(mv[2])) {
                    throw new IllegalArgumentException("line " + (n + 1) + ": want a move and a level, like B T19 CLEAN: " + item.trim());
                }
                moves.add(new Judged(mv[0] + " " + mv[1], mv[2]));
            }
            out.add(new Entry(parts[0].trim(), at, moves));
        }
        return out;
    }

    private KataBrain restart(KataBrain brain) throws Exception {
        System.out.println("katago died, restarting the engine");
        brain.stopKataBrain();
        return new KataBrain(props);
    }
}
