package autoprob.validate;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import autoprob.KataBrain;
import autoprob.go.Node;
import autoprob.go.action.CommentAction;
import autoprob.go.parse.Parser;
import autoprob.validate.SolutionPathGenerator.GenResult;

// takes an existing, well crafted problem, excises the correct solution paths,
// regenerates the correct part of the solution tree from scratch with katago + human
// policy, and then scores it against the original human crafted paths: the main line
// against the closest author line, and the whole tree against the author's correct tree.
// this lets us measure how close our path creation is to high quality human data.
public class ProblemRedoer {
    private final Properties props;

    public static class RedoResult {
        public String file;
        public String error;
        public int refCount;
        public List<String> refFirstMoves = new ArrayList<>();
        public MovePath generated; // the main line: the first choice at every turn
        public List<MovePath> generatedLines = new ArrayList<>(); // every line of the generated tree
        public TreeComparison tree;
        public boolean endedNaturally;
        public MovePath bestRef;
        public int bestPrefix;
        public boolean firstMoveMatch;
        public boolean exact;
        public int inTreeDepth; // how long the generated line stays inside the original tree
        public boolean endsOnRight; // the deepest in-tree node reached is a RIGHT node
        public List<String> notes = new ArrayList<>();
        public String path; // the problem sgf as given
        public String sha;
        public List<MovePath> refs = new ArrayList<>();
        public IsolationRecord isolation; // the isolation the kept generation ran on
        public List<DecisionRecord> decisions = new ArrayList<>();

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

        RunRecorder recorder = Boolean.parseBoolean(props.getProperty("redo.record", "true"))
                ? new RunRecorder(props) : null;
        KataBrain brain = new KataBrain(props);
        List<RedoResult> results = new ArrayList<>();
        int engineRestarts = 0;
        try {
            for (File file : files) {
                RedoResult result = redo(brain, file);
                // if katago died mid problem, restart it and retry this file once
                if (!brain.isAlive() && engineRestarts < 3) {
                    engineRestarts++;
                    System.out.println("katago died, restarting engine (restart " + engineRestarts
                            + ") and retrying " + file.getName());
                    brain.stopKataBrain();
                    brain = new KataBrain(props);
                    result = redo(brain, file);
                }
                results.add(result);
                if (recorder != null) {
                    recorder.writeProblem(toRecord(result));
                }
            }
        } finally {
            brain.stopKataBrain();
        }

        printSummary(results);
        writeCsv(results);
        if (recorder != null) {
            recorder.finish(results);
            System.out.println("run recorded in " + recorder.getDir().getPath());
        }
    }

    // redo one problem file; errors are captured in the result rather than thrown
    public RedoResult redo(KataBrain brain, File file) {
        System.out.println();
        System.out.println("======================================================");
        System.out.println("redoing problem: " + file.getName());
        System.out.println("======================================================");
        RedoResult result = new RedoResult();
        result.file = file.getName();
        result.path = file.getPath().replace(File.separatorChar, '/');
        try {
            redoFile(brain, file, result);
        } catch (Exception e) {
            e.printStackTrace();
            result.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        return result;
    }

    public static ProblemRecord toRecord(RedoResult r) {
        ProblemRecord rec = new ProblemRecord();
        rec.problem = r.file.replaceAll("\\.sgf$", "");
        rec.file = r.path;
        rec.sha = r.sha;
        rec.error = r.error;
        rec.isolation = r.isolation;
        rec.decisions = r.decisions;
        rec.notes = r.notes;
        for (MovePath ref : r.refs) {
            rec.refs.add(ProblemRecord.moves(ref));
        }
        if (r.generated != null) {
            rec.generated = ProblemRecord.moves(r.generated);
        }
        for (MovePath line : r.generatedLines) {
            rec.generatedLines.add(ProblemRecord.moves(line));
        }
        rec.tree = r.tree;
        if (r.error == null && r.generated != null && r.bestRef != null) {
            ProblemRecord.Metrics m = new ProblemRecord.Metrics();
            m.firstMoveMatch = r.firstMoveMatch;
            m.prefix = r.bestPrefix;
            m.genLen = r.generated.length();
            m.refLen = r.bestRef.length();
            m.delta = r.lengthDelta();
            m.exact = r.exact;
            m.endedNaturally = r.endedNaturally;
            m.inTreeDepth = r.inTreeDepth;
            m.endsOnRight = r.endsOnRight;
            rec.metrics = m;
        }
        return rec;
    }

    private void redoFile(KataBrain brain, File file, RedoResult result) throws Exception {
        byte[] bytes = Files.readAllBytes(file.toPath());
        result.sha = RunRecorder.sha1(bytes);
        String sgf = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        Parser parser = new Parser();
        Node root = parser.parse(sgf);

        // the human crafted reference: all paths ending in RIGHT
        List<MovePath> refPaths = MovePath.extractRightPaths(root);
        result.refs = refPaths;
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
        result.notes.addAll(iso.warnings);
        writeIsolatedSgf(file, iso);

        // regenerate the solution line
        boolean forceFirst = Boolean.parseBoolean(props.getProperty("redo.force_first_move", "false"));
        SolutionPathGenerator generator = new SolutionPathGenerator(props, brain, iso);
        if (forceFirst && !refPaths.isEmpty()) {
            generator.setForcedFirstMove(refPaths.get(0).moves.get(0).loc);
        }
        GenResult gen = generator.generate();
        result.isolation = IsolationRecord.from(iso); // before recalibration can change it

        // if the score drifted, the isolation baseline missed the solution's value:
        // recalibrate stakes and komi from the discovered line and regenerate once
        if (gen.endNode != null && hasDriftNote(gen.notes)
                && Boolean.parseBoolean(props.getProperty("redo.recalibrate", "true"))) {
            System.out.println("=== recalibrating isolation from the discovered line ===");
            boolean baselineMoved = isolator.recalibrate(iso, gen.endNode);
            writeIsolatedSgf(file, iso); // refresh with corrected stakes and komi
            if (baselineMoved) {
                System.out.println("=== baseline was wrong, regenerating ===");
                iso.problem.removeAllChildren(); // discard the first generation's tree
                generator = new SolutionPathGenerator(props, brain, iso);
                if (forceFirst && !refPaths.isEmpty()) {
                    generator.setForcedFirstMove(refPaths.get(0).moves.get(0).loc);
                }
                GenResult gen2 = generator.generate();
                if (gen2.endNode != null) {
                    gen2.notes.add(0, "regenerated after recalibration");
                    gen = gen2;
                    result.isolation = IsolationRecord.from(iso);
                }
            } else {
                System.out.println("end of line was on target, keeping the first generation");
            }
        }
        result.notes.addAll(gen.notes);
        result.decisions = gen.decisions;
        result.endedNaturally = gen.endedNaturally;
        if (gen.endNode == null) {
            throw new RuntimeException("no path was generated");
        }
        result.generated = MovePath.fromEndNode(gen.endNode);
        result.generatedLines = MovePath.extractRightPaths(iso.problem);
        System.out.println("generated tree (" + result.generatedLines.size() + " lines):");
        for (MovePath line : result.generatedLines) {
            System.out.println("  " + line);
        }
        result.tree = TreeComparison.compare(lines(result.generatedLines), lines(refPaths));

        evaluate(result, refPaths);
        evaluateTreeWalk(result, root);
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

    static List<List<String>> lines(List<MovePath> paths) {
        List<List<String>> out = new ArrayList<>();
        for (MovePath p : paths) out.add(ProblemRecord.moves(p));
        return out;
    }

    private boolean hasDriftNote(List<String> notes) {
        for (String note : notes) {
            if (note.contains("score drifted")) return true;
        }
        return false;
    }

    // walk the original tree along the generated moves: a generated move that exists
    // anywhere in the tree (even off the RIGHT paths) was considered by the author.
    // this separates legitimate alternative resistance from genuinely foreign moves.
    private void evaluateTreeWalk(RedoResult result, Node root) {
        Node cur = root;
        int depth = 0;
        for (MovePath.Move move : result.generated.moves) {
            Node next = cur.getChildWithMove(move.loc);
            if (next == null) break;
            cur = next;
            depth++;
        }
        result.inTreeDepth = depth;
        result.endsOnRight = cur.result == autoprob.go.Intersection.RIGHT;
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
                + ", exact: " + (result.exact ? "yes" : "no")
                + ", in tree: " + result.inTreeDepth + "/" + result.generated.length()
                + (result.endsOnRight ? " ending RIGHT" : ""));
        TreeComparison tc = result.tree;
        System.out.println("tree: " + tc.summary());
        for (String m : tc.missingSolverMoves) System.out.println("  missing solver move   " + m);
        for (String m : tc.extraSolverMoves) System.out.println("  extra solver move     " + m);
        for (String m : tc.missingResponses) System.out.println("  missing response      " + m);
        for (String m : tc.extraResponses) System.out.println("  extra response        " + m);
        for (String m : tc.endsEarly) System.out.println("  ends early at         " + m);
        for (String m : tc.endsLate) System.out.println("  continues past end at " + m);
        for (String note : result.notes) {
            System.out.println("note: " + note);
        }
    }

    // write the exact board the engine evaluates (fortress fill + balanced komi),
    // so it can be loaded in a GUI to inspect what katago sees. note the tool
    // queries with tromp-taylor rules, so match that when analyzing by hand.
    private void writeIsolatedSgf(File file, ProblemIsolator.IsolatedProblem iso) throws Exception {
        if (!Boolean.parseBoolean(props.getProperty("redo.output.isolated", "false"))) {
            return;
        }
        // build a throwaway copy so the live problem node stays untouched
        Node out = new Node(null);
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++)
                out.board.board[x][y].stone = iso.problem.board.board[x][y].stone;
        out.addXtraTag("KM", String.valueOf(iso.komi));
        out.addXtraTag("PL", iso.solverColor == autoprob.go.Intersection.BLACK ? "B" : "W");
        // mark the stones the tool believes are at stake
        StringBuilder stakes = new StringBuilder();
        for (java.awt.Point p : iso.stakes) {
            out.addAct(new autoprob.go.action.TriangleAction(p.x, p.y));
            if (stakes.length() > 0) stakes.append(" ");
            stakes.append(autoprob.go.Intersection.toGTPloc(p.x, p.y, 19));
        }
        out.addAct(new CommentAction("isolated evaluation board (tromp-taylor rules). komi " + iso.komi
                + ", " + (iso.solverColor == autoprob.go.Intersection.BLACK ? "black" : "white")
                + " to solve. triangles mark the stones whose fate the tool thinks depends on solving: " + stakes));

        String baseName = file.getName().replaceAll("\\.sgf$", "");
        File outFile = new File(getOutDir(), baseName + "_isolated.sgf");
        try (PrintWriter writer = new PrintWriter(outFile)) {
            writer.println("(" + out.outputSGF(true) + ")");
        }
        System.out.println("wrote isolated board: " + outFile.getPath());
    }

    private File getOutDir() {
        String outDirName = props.getProperty("redo.output.dir", "redo_out");
        File outDir = new File(outDirName);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new RuntimeException("cannot create output directory: " + outDirName);
        }
        return outDir;
    }

    // write the original problem with the generated tree grafted in for eyeballing: each
    // generated first move is a new variation commented GENERATED, with its branches below
    private void writeOutputSgf(File file, String sgf, RedoResult result) throws Exception {
        if (!Boolean.parseBoolean(props.getProperty("redo.write_file", "true"))) {
            return;
        }
        File outDir = getOutDir();

        // fresh parse so we do not disturb evaluation state
        Parser parser = new Parser();
        Node root = parser.parse(sgf);
        Map<String, Node> grafted = new HashMap<>();
        for (MovePath line : result.generatedLines) {
            Node cur = root;
            List<String> at = new ArrayList<>();
            for (MovePath.Move move : line.moves) {
                at.add(move.toString());
                String key = DecisionRecord.key(at);
                Node next = grafted.get(key);
                if (next == null) {
                    next = cur.addBasicMove(move.loc.x, move.loc.y);
                    if (at.size() == 1) next.addAct(new CommentAction("GENERATED"));
                    grafted.put(key, next);
                }
                cur = next;
            }
            cur.result = autoprob.go.Intersection.RIGHT;
        }

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
        int complete = 0, missingSolver = 0, extraSolver = 0, authorLines = 0, reproduced = 0;
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
            TreeComparison tc = r.tree;
            if (tc.missingSolverMoves.isEmpty()) complete++;
            missingSolver += tc.missingSolverMoves.size();
            extraSolver += tc.extraSolverMoves.size();
            authorLines += tc.authorLines;
            reproduced += tc.reproduced;
            System.out.println(pad(r.file, 28)
                    + " first:" + (r.firstMoveMatch ? "y" : "N")
                    + " prefix:" + r.bestPrefix
                    + " gen:" + r.generated.length()
                    + " ref:" + r.bestRef.length()
                    + " delta:" + (r.lengthDelta() >= 0 ? "+" : "") + r.lengthDelta()
                    + " exact:" + (r.exact ? "y" : "n")
                    + " tree:" + r.inTreeDepth + (r.endsOnRight ? "R" : "")
                    + " lines:" + tc.generatedLines + "/" + tc.authorLines + " repro:" + tc.reproduced
                    + " solver-missing:" + tc.missingSolverMoves.size() + " extra:" + tc.extraSolverMoves.size()
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
            System.out.println("every author solver move generated: " + complete + "/" + ok
                    + " (missing " + missingSolver + ", extra " + extraSolver + ")");
            System.out.println("author lines reproduced: " + reproduced + "/" + authorLines);
        }
    }

    private void writeCsv(List<RedoResult> results) throws Exception {
        String csvPath = props.getProperty("csvout.path");
        if (csvPath == null) {
            return;
        }
        try (PrintWriter writer = new PrintWriter(csvPath)) {
            writer.println("file,error,refpaths,firstmatch,prefix,genlen,reflen,delta,exact,natural,intree,endsright,"
                    + "genlines,reproduced,solvermissing,solverextra,respmissing,respextra,endsearly,endslate,generated");
            for (RedoResult r : results) {
                if (r.error != null) {
                    writer.println(r.file + "," + r.error.replace(',', ';') + ",,,,,,,,,,,,,,,,,,,");
                    continue;
                }
                writer.println(r.file + ",," + r.refCount + "," + r.firstMoveMatch + "," + r.bestPrefix
                        + "," + r.generated.length() + "," + r.bestRef.length() + "," + r.lengthDelta()
                        + "," + r.exact + "," + r.endedNaturally
                        + "," + r.inTreeDepth + "," + r.endsOnRight
                        + "," + r.tree.generatedLines + "," + r.tree.reproduced + "," + r.tree.missingSolverMoves.size()
                        + "," + r.tree.extraSolverMoves.size() + "," + r.tree.missingResponses.size()
                        + "," + r.tree.extraResponses.size() + "," + r.tree.endsEarly.size() + "," + r.tree.endsLate.size()
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
