package autoprob.validate;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import autoprob.KataBrain;
import autoprob.go.Node;
import autoprob.go.action.CommentAction;
import autoprob.go.parse.Parser;
import autoprob.validate.SolutionPathGenerator.GenResult;

// takes an existing, well crafted problem, excises the correct solution paths,
// regenerates a solution line from scratch with katago + human policy, and then
// scores the regenerated line against the original human crafted paths.
// this lets us measure how close our path creation is to high quality human data.
public class ProblemRedoer {
    private final Properties props;

    public static class RedoResult {
        public String file;
        public String error;
        public int refCount;
        public List<String> refFirstMoves = new ArrayList<>();
        public MovePath generated;
        public boolean endedNaturally;
        public MovePath bestRef;
        public int bestPrefix;
        public boolean firstMoveMatch;
        public boolean exact;
        public List<String> notes = new ArrayList<>();

        public int lengthDelta() {
            return generated.length() - bestRef.length();
        }
    }

    public ProblemRedoer(Properties props) {
        this.props = props;
    }

    public void run() throws Exception {
        String path = props.getProperty("path");
        if (path == null) {
            throw new RuntimeException("you must pass in a path to an SGF problem file or directory");
        }
        File f = new File(path);
        if (!f.exists()) {
            throw new RuntimeException("no such file or directory: " + path);
        }

        List<File> files = new ArrayList<>();
        if (f.isFile()) {
            files.add(f);
        } else {
            File[] listing = f.listFiles();
            if (listing == null) {
                throw new RuntimeException("no files in directory: " + path);
            }
            for (File file : listing) {
                if (file.isFile() && file.getName().endsWith(".sgf")) {
                    files.add(file);
                }
            }
            files.sort((a, b) -> a.getName().compareTo(b.getName()));
        }

        KataBrain brain = new KataBrain(props);
        List<RedoResult> results = new ArrayList<>();
        try {
            for (File file : files) {
                System.out.println();
                System.out.println("======================================================");
                System.out.println("redoing problem: " + file.getName());
                System.out.println("======================================================");
                RedoResult result = new RedoResult();
                result.file = file.getName();
                try {
                    redoFile(brain, file, result);
                } catch (Exception e) {
                    e.printStackTrace();
                    result.error = e.getMessage();
                }
                results.add(result);
            }
        } finally {
            brain.stopKataBrain();
        }

        printSummary(results);
        writeCsv(results);
    }

    private void redoFile(KataBrain brain, File file, RedoResult result) throws Exception {
        String sgf = Files.readString(file.toPath());
        Parser parser = new Parser();
        Node root = parser.parse(sgf);

        // the human crafted reference: all paths ending in RIGHT
        List<MovePath> refPaths = MovePath.extractRightPaths(root);
        result.refCount = refPaths.size();
        if (refPaths.isEmpty()) {
            throw new RuntimeException("no RIGHT paths in problem, cannot evaluate");
        }
        Set<String> firstMoves = new HashSet<>();
        for (MovePath ref : refPaths) {
            if (ref.length() > 0) {
                firstMoves.add(ref.moves.get(0).gtp());
            }
        }
        result.refFirstMoves.addAll(firstMoves);
        System.out.println("reference RIGHT paths: " + refPaths.size()
                + ", distinct first moves: " + firstMoves);
        for (int i = 0; i < refPaths.size(); i++) {
            System.out.println("  ref " + (i + 1) + " (" + refPaths.get(i).length() + " moves): " + refPaths.get(i));
        }

        // excise and isolate: fresh board, fortress fill, balanced komi
        ProblemIsolator isolator = new ProblemIsolator(props, brain);
        ProblemIsolator.IsolatedProblem iso = isolator.isolate(root);
        System.out.println(iso.problem.board);

        // regenerate the solution line
        SolutionPathGenerator generator = new SolutionPathGenerator(props, brain, iso);
        GenResult gen = generator.generateMainLine();
        result.notes.addAll(gen.notes);
        result.endedNaturally = gen.endedNaturally;
        if (gen.endNode == null) {
            throw new RuntimeException("no path was generated");
        }
        result.generated = MovePath.fromEndNode(gen.endNode);
        System.out.println("generated (" + result.generated.length() + " moves): " + result.generated);

        evaluate(result, refPaths);
        writeOutputSgf(file, sgf, result);
        printFileReport(result);
    }

    // compare the generated line against the reference paths, keep the best match
    private void evaluate(RedoResult result, List<MovePath> refPaths) {
        MovePath best = null;
        int bestPrefix = -1;
        for (MovePath ref : refPaths) {
            int prefix = result.generated.commonPrefix(ref);
            boolean better = prefix > bestPrefix
                    || (prefix == bestPrefix && best != null
                        && Math.abs(result.generated.length() - ref.length())
                           < Math.abs(result.generated.length() - best.length()));
            if (better) {
                bestPrefix = prefix;
                best = ref;
            }
        }
        result.bestRef = best;
        result.bestPrefix = bestPrefix;
        result.firstMoveMatch = bestPrefix >= 1;
        result.exact = bestPrefix == result.generated.length() && bestPrefix == best.length();
    }

    private void printFileReport(RedoResult result) {
        System.out.println();
        System.out.println("--- report for " + result.file + " ---");
        System.out.println("generated: " + result.generated);
        System.out.println("best ref:  " + result.bestRef);
        System.out.println("first move match: " + (result.firstMoveMatch ? "yes" : "NO")
                + ", common prefix: " + result.bestPrefix
                + ", generated " + result.generated.length() + " vs ref " + result.bestRef.length()
                + " moves (delta " + (result.lengthDelta() >= 0 ? "+" : "") + result.lengthDelta() + ")"
                + ", exact: " + (result.exact ? "yes" : "no"));
        for (String note : result.notes) {
            System.out.println("note: " + note);
        }
    }

    // write the original problem with the generated line grafted in for eyeballing
    private void writeOutputSgf(File file, String sgf, RedoResult result) throws Exception {
        if (!Boolean.parseBoolean(props.getProperty("redo.write_file", "true"))) {
            return;
        }
        String outDirName = props.getProperty("redo.output.dir", "redo_out");
        File outDir = new File(outDirName);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new RuntimeException("cannot create output directory: " + outDirName);
        }

        // fresh parse so we do not disturb evaluation state
        Parser parser = new Parser();
        Node root = parser.parse(sgf);
        Node cur = root;
        for (int i = 0; i < result.generated.length(); i++) {
            MovePath.Move move = result.generated.moves.get(i);
            cur = cur.addBasicMove(move.loc.x, move.loc.y);
            if (i == 0) {
                cur.addAct(new CommentAction("GENERATED"));
            }
        }
        cur.result = autoprob.go.Intersection.RIGHT;

        String baseName = file.getName().replaceAll("\\.sgf$", "");
        File outFile = new File(outDir, baseName + "_redo.sgf");
        try (PrintWriter writer = new PrintWriter(outFile)) {
            writer.println("(" + root.outputSGF(true) + ")");
        }
        System.out.println("wrote: " + outFile.getPath());
    }

    private void printSummary(List<RedoResult> results) {
        System.out.println();
        System.out.println("==== SUMMARY (" + results.size() + " problems) ====");
        int ok = 0, firstMove = 0, exact = 0, natural = 0;
        int totalPrefix = 0, totalGenLen = 0, totalRefLen = 0, totalAbsDelta = 0;
        for (RedoResult r : results) {
            if (r.error != null) {
                System.out.println(pad(r.file, 28) + " ERROR: " + r.error);
                continue;
            }
            ok++;
            if (r.firstMoveMatch) firstMove++;
            if (r.exact) exact++;
            if (r.endedNaturally) natural++;
            totalPrefix += r.bestPrefix;
            totalGenLen += r.generated.length();
            totalRefLen += r.bestRef.length();
            totalAbsDelta += Math.abs(r.lengthDelta());
            System.out.println(pad(r.file, 28)
                    + " first:" + (r.firstMoveMatch ? "y" : "N")
                    + " prefix:" + r.bestPrefix
                    + " gen:" + r.generated.length()
                    + " ref:" + r.bestRef.length()
                    + " delta:" + (r.lengthDelta() >= 0 ? "+" : "") + r.lengthDelta()
                    + " exact:" + (r.exact ? "y" : "n")
                    + (r.endedNaturally ? "" : " (truncated)"));
        }
        if (ok > 0) {
            System.out.println();
            System.out.println("evaluated: " + ok + "/" + results.size());
            System.out.println("first move matched: " + firstMove + "/" + ok);
            System.out.println("exact path match: " + exact + "/" + ok);
            System.out.println("ended naturally: " + natural + "/" + ok);
            System.out.println("avg common prefix: " + df1(totalPrefix / (double) ok)
                    + ", avg generated length: " + df1(totalGenLen / (double) ok)
                    + ", avg ref length: " + df1(totalRefLen / (double) ok)
                    + ", avg |length delta|: " + df1(totalAbsDelta / (double) ok));
        }
    }

    private void writeCsv(List<RedoResult> results) throws Exception {
        String csvPath = props.getProperty("csvout.path");
        if (csvPath == null) {
            return;
        }
        try (PrintWriter writer = new PrintWriter(csvPath)) {
            writer.println("file,error,refpaths,firstmatch,prefix,genlen,reflen,delta,exact,natural,generated");
            for (RedoResult r : results) {
                if (r.error != null) {
                    writer.println(r.file + "," + r.error.replace(',', ';') + ",,,,,,,,,");
                    continue;
                }
                writer.println(r.file + ",," + r.refCount + "," + r.firstMoveMatch + "," + r.bestPrefix
                        + "," + r.generated.length() + "," + r.bestRef.length() + "," + r.lengthDelta()
                        + "," + r.exact + "," + r.endedNaturally
                        + "," + r.generated.toString().replace(',', ' '));
            }
        }
        System.out.println("wrote csv: " + csvPath);
    }

    private static String pad(String s, int len) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < len) sb.append(' ');
        return sb.toString();
    }

    private static String df1(double d) {
        return String.format("%.1f", d);
    }
}
