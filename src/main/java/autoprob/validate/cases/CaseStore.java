package autoprob.validate.cases;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import autoprob.validate.RunRecorder;

// reads and writes the case library: one json file per problem in cases/ (or cases.dir).
// files are written with a stable decision order so git diffs stay small.
public class CaseStore {
    private final File dir;

    public CaseStore(Properties props) {
        this(new File(props.getProperty("cases.dir", "cases")));
    }

    public CaseStore(File dir) {
        this.dir = dir;
    }

    public File getDir() {
        return dir;
    }

    public CaseFile load(String problem) throws IOException {
        File f = file(problem);
        if (!f.exists()) return null;
        return RunRecorder.GSON.fromJson(Files.readString(f.toPath()), CaseFile.class);
    }

    public CaseFile loadOrCreate(String problem) throws IOException {
        CaseFile cf = load(problem);
        if (cf == null) {
            cf = new CaseFile();
            cf.problem = problem;
        }
        return cf;
    }

    // every case file, optionally only the named problems (comma separated)
    public List<CaseFile> loadAll(String problemFilter) throws IOException {
        Set<String> only = new HashSet<>();
        if (problemFilter != null && !problemFilter.isBlank()) {
            only.addAll(Arrays.asList(problemFilter.split(",")));
        }
        List<CaseFile> out = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return out;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            CaseFile cf = RunRecorder.GSON.fromJson(Files.readString(f.toPath()), CaseFile.class);
            if (only.isEmpty() || only.contains(cf.problem)) out.add(cf);
        }
        return out;
    }

    public void save(CaseFile cf) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create case directory: " + dir);
        }
        cf.decisions.sort(Comparator.comparingInt((DecisionCase c) -> c.at.size()).thenComparing(DecisionCase::key));
        Files.writeString(file(cf.problem).toPath(), RunRecorder.GSON.toJson(cf) + "\n");
    }

    private File file(String problem) {
        return new File(dir, problem + ".json");
    }
}
