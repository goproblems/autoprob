package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrawlReviewServerTest {
    @TempDir
    Path tmp;

    CrawlReviewServer server() {
        Properties props = new Properties();
        props.setProperty("crawl.dir", tmp.toString());
        return new CrawlReviewServer(props);
    }

    static SuggestionDraft draft(int id) {
        SuggestionDraft d = new SuggestionDraft();
        d.problemId = id;
        d.problem = "gp-" + id;
        return d;
    }

    static MissingVariationFinder.Finding finding(MoveClassifier.Level level, String... at) {
        MissingVariationFinder.Finding f = new MissingVariationFinder.Finding();
        f.at = List.of(at);
        f.level = level;
        return f;
    }

    @Test
    void theHeadlineSaysWhatTheCrawlMadeOfTheProblemsCorrectLines() throws Exception {
        Files.writeString(tmp.resolve("problems.jsonl"), """
                {"id":4,"status":"found","result":"seki"}
                {"id":5,"status":"found"}
                {"id":4,"status":"found","result":"kill"}
                """);
        // a later line replaces an earlier
        assertEquals(Map.of("text", "kill"), server().result(draft(4)));
        // judged before the crawl kept it: the shallowest finding's level, where live and kill are one
        Files.createDirectories(tmp.resolve("findings"));
        Files.writeString(tmp.resolve("findings/gp-5.json"), RunRecorder.GSON.toJson(List.of(
                finding(MoveClassifier.Level.KO, "B E2", "W F2"), finding(MoveClassifier.Level.CLEAN, "B E2"))));
        assertEquals(Map.of("text", "live or kill", "old", true), server().result(draft(5)));
        Files.writeString(tmp.resolve("findings/gp-5.json"), RunRecorder.GSON.toJson(List.of(
                finding(MoveClassifier.Level.SEKI), finding(MoveClassifier.Level.KO, "B E2", "W F2"))));
        assertEquals(Map.of("text", "seki", "old", true), server().result(draft(5)));
        // nothing kept and no findings: nothing to say
        assertNull(server().result(draft(6)));
    }
}
