package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrawlIndexTest {
    @TempDir
    Path tmp;

    static CrawlIndex.Entry entry(int id, String status, Integer version, int attempts) {
        CrawlIndex.Entry e = new CrawlIndex.Entry();
        e.id = id;
        e.status = status;
        e.version = version;
        e.attempts = attempts;
        return e;
    }

    @Test
    void aLaterLineForAProblemReplacesAnEarlierOneAndACutLineIsPassedOver() throws Exception {
        File f = tmp.resolve("problems.jsonl").toFile();
        Files.writeString(f.toPath(), "{\"id\":5,\"status\":\"error\",\"attempts\":1}\n{\"id\":4,\"status\":\"clean\"}\n"
                + "{\"id\":5,\"status\":\"found\",\"version\":1}\n{\"id\":3,\"sta");
        CrawlIndex index = CrawlIndex.load(f);
        assertEquals("found", index.get(5).status);
        assertEquals("clean", index.get(4).status);
        assertNull(index.get(3));
        // the next line starts on a line of its own, after the cut one
        index.append(entry(3, "skipped", null, 0));
        CrawlIndex again = CrawlIndex.load(f);
        assertEquals("skipped", again.get(3).status);
        assertEquals(3, again.entries().size());
    }

    @Test
    void whatIsDoneAndWhatIsLookedAtAgain() throws Exception {
        CrawlIndex index = CrawlIndex.load(tmp.resolve("problems.jsonl").toFile());
        index.append(entry(1, "found", 1, 0));
        index.append(entry(2, "clean", 1, 0));
        index.append(entry(3, "skipped", null, 0));
        index.append(entry(4, "error", null, 1));
        index.append(entry(5, "error", null, 2));
        index.append(entry(6, "deferred", null, 0));
        index.append(entry(7, "stale", null, 0));
        for (int id : new int[]{1, 2, 3, 5}) assertTrue(index.done(id, 2, Set.of(), 1), "done: " + id);
        for (int id : new int[]{4, 6, 7, 8}) assertFalse(index.done(id, 2, Set.of(), 1), "not done: " + id);
        // a newer judging rechecks what an older one found, and only that
        assertFalse(index.done(1, 2, Set.of("found"), 2));
        assertTrue(index.done(2, 2, Set.of("found"), 2));
        assertTrue(index.done(3, 2, Set.of("found", "skipped"), 2));
    }
}
