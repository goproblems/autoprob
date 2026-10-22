package autoprob.validate;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

// writes a redo run to redo_runs/<id>/: manifest.json describing the run (code version,
// engine, settings, summary) and one <problem>.json per problem. problem files are
// written as each problem finishes, so a run that dies part way is still usable.
public class RunRecorder {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static class Manifest {
        public String id;
        public String started;
        public String finished;
        public String commit;
        public String katago;
        public String model;
        public String humanModel;
        public String kataConfig;
        public String path;
        public TreeMap<String, String> settings = new TreeMap<>();
        public List<String> problems = new ArrayList<>();
        public Summary summary;
    }

    public static class Summary {
        public int problems;
        public int evaluated;
        public int firstMoveMatch;
        public int exact;
        public int endedNaturally;
    }

    private final File dir;
    private final Manifest manifest = new Manifest();

    public RunRecorder(Properties props) throws IOException {
        File root = new File(props.getProperty("redo.runs.dir", "redo_runs"));
        manifest.id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        dir = new File(root, manifest.id);
        if (!dir.mkdirs()) {
            throw new IOException("cannot create run directory: " + dir);
        }
        manifest.started = LocalDateTime.now().toString();
        manifest.commit = gitCommit();
        manifest.katago = props.getProperty("katago");
        manifest.model = props.getProperty("kata.model");
        manifest.humanModel = props.getProperty("kata.human_model");
        manifest.kataConfig = props.getProperty("kata.config");
        manifest.path = props.getProperty("path");
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("redo.")) manifest.settings.put(name, props.getProperty(name));
        }
        writeManifest();
        System.out.println("recording run to " + dir.getPath());
    }

    public File getDir() {
        return dir;
    }

    public String getCommit() {
        return manifest.commit;
    }

    public void writeProblem(ProblemRecord rec) throws IOException {
        manifest.problems.add(rec.problem);
        Files.writeString(new File(dir, rec.problem + ".json").toPath(), GSON.toJson(rec));
        writeManifest();
    }

    public void finish(List<ProblemRedoer.RedoResult> results) throws IOException {
        Summary s = new Summary();
        s.problems = results.size();
        for (ProblemRedoer.RedoResult r : results) {
            if (r.error != null) continue;
            s.evaluated++;
            if (r.firstMoveMatch) s.firstMoveMatch++;
            if (r.exact) s.exact++;
            if (r.endedNaturally) s.endedNaturally++;
        }
        manifest.summary = s;
        manifest.finished = LocalDateTime.now().toString();
        writeManifest();
    }

    private void writeManifest() throws IOException {
        Files.writeString(new File(dir, "manifest.json").toPath(), GSON.toJson(manifest));
    }

    // the latest run directory under root, by name (ids sort by time)
    public static File latestRun(File root) {
        File[] runs = root.listFiles(File::isDirectory);
        if (runs == null || runs.length == 0) return null;
        File latest = runs[0];
        for (File f : runs) {
            if (f.getName().compareTo(latest.getName()) > 0) latest = f;
        }
        return latest;
    }

    // resolve a run= argument: a directory, a run id under redo_runs, or latest
    public static File resolveRun(Properties props) {
        File root = new File(props.getProperty("redo.runs.dir", "redo_runs"));
        String run = props.getProperty("run", "latest");
        File dir = run.equals("latest") ? latestRun(root) : new File(run);
        if (dir != null && !dir.isDirectory()) dir = new File(root, run);
        if (dir == null || !dir.isDirectory()) {
            throw new RuntimeException("no redo run found for run=" + run + " (looked in " + root + ")");
        }
        return dir;
    }

    public static Manifest readManifest(File runDir) throws IOException {
        return GSON.fromJson(Files.readString(new File(runDir, "manifest.json").toPath()), Manifest.class);
    }

    public static ProblemRecord readProblem(File runDir, String problem) throws IOException {
        return GSON.fromJson(Files.readString(new File(runDir, problem + ".json").toPath()), ProblemRecord.class);
    }

    public static String sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // short commit hash, with -dirty when tracked files have uncommitted changes
    public static String gitCommit() {
        try {
            String head = run("git", "rev-parse", "--short", "HEAD").trim();
            if (head.isEmpty()) return "unknown";
            String status = run("git", "status", "--porcelain", "--untracked-files=no").trim();
            return status.isEmpty() ? head : head + "-dirty";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (InputStream in = p.getInputStream()) {
            String out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? out : "";
        }
    }
}
