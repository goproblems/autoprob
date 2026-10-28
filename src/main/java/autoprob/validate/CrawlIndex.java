package autoprob.validate;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

// what the crawl has made of each problem it looked at, the crawl's memory between runs: one
// json line per problem in problems.jsonl, appended as each is done, a later line for the same
// problem replacing an earlier one. a line cut short by a stop is passed over on reading
public class CrawlIndex {
    // found, unsuggested (correct moves missing that no suggestion could carry), clean and
    // skipped are done; error is tried again up to the attempts allowed; deferred, a problem
    // with a suggestion open on the site, and stale, one changed on the site since its
    // suggestion was made, are looked at again
    public static final Set<String> DONE = Set.of("found", "unsuggested", "clean", "skipped");

    public static class Entry {
        public int id;
        public String status;
        public String reason;
        public Integer version;     // the judging version, for a problem that was judged
        public String difficulty;
        public String sgfHash;      // of the sgf as the site had it
        public Integer positions;   // positions on the author's lines checked
        public Long seconds;
        public int attempts;        // errors so far
        public String time;
    }

    private static final Gson GSON = new Gson();
    private final File file;
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private boolean cutShort; // the file ends inside a line: the next one starts on a new line

    private CrawlIndex(File file) {
        this.file = file;
    }

    public static CrawlIndex load(File file) throws IOException {
        CrawlIndex index = new CrawlIndex(file);
        if (!file.exists()) return index;
        byte[] bytes = Files.readAllBytes(file.toPath());
        index.cutShort = bytes.length > 0 && bytes[bytes.length - 1] != '\n';
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                Entry e = GSON.fromJson(line, Entry.class);
                if (e != null && e.status != null) index.entries.put(e.id, e);
            } catch (JsonParseException e) {
                // a line cut short when the crawl was stopped
            }
        }
        return index;
    }

    public Entry get(int id) {
        return entries.get(id);
    }

    public Collection<Entry> entries() {
        return entries.values();
    }

    // whether the problem needs no more looking at: done, or out of attempts after errors, or
    // judged by an older version when its status is one to recheck
    public boolean done(int id, int maxAttempts, Set<String> recheck, int version) {
        Entry e = entries.get(id);
        if (e == null) return false;
        if (e.status.equals("error")) return e.attempts >= maxAttempts;
        if (!DONE.contains(e.status)) return false;
        return !(recheck.contains(e.status) && e.version != null && e.version < version);
    }

    // a line on its own, so a stop can cut short at most the last one
    public void append(Entry e) throws IOException {
        entries.put(e.id, e);
        Files.writeString(file.toPath(), (cutShort ? "\n" : "") + GSON.toJson(e) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        cutShort = false;
    }
}
