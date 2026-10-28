package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;

import autoprob.go.parse.SgfEditTest;

class CrawlerTest {
    @TempDir
    Path tmp;

    // the site: problems by id, the newest the highest
    static class FakeSite implements Crawler.Site {
        final Map<Integer, JsonObject> problems = new HashMap<>();

        public int newestId() {
            return problems.keySet().stream().max(Integer::compare).orElse(0);
        }

        public JsonObject problem(int id) {
            return problems.get(id);
        }

        void add(int id, String genre, boolean standard, boolean sandbox, int open) {
            JsonObject p = new JsonObject();
            p.addProperty("id", id);
            p.addProperty("genre", genre);
            p.addProperty("isStandard", standard);
            p.addProperty("alive", true);
            p.addProperty("sandbox", sandbox);
            p.addProperty("suggestionsOpenCount", open);
            p.addProperty("sgf", SgfEditTest.GP62070);
            JsonObject rank = new JsonObject();
            rank.addProperty("value", 18);
            rank.addProperty("unit", "kyu");
            p.add("rank", rank);
            problems.put(id, p);
        }
    }

    // the finder: R19 missing in the problems listed, nothing in the others
    static class FakeAnalyzer implements Crawler.Analyzer {
        final List<String> judged = new ArrayList<>();
        final List<String> headings = new ArrayList<>();
        final List<String> finds = new ArrayList<>();

        public MissingVariationFinder.Outcome analyze(String name, String sgf, String difficulty, String heading) {
            judged.add(name);
            headings.add(heading);
            MissingVariationFinder.Outcome o = new MissingVariationFinder.Outcome();
            o.problem = name;
            o.positions = 5;
            if (finds.contains(name)) {
                o.findings.add(SuggestionDraftTest.r19());
                o.finding = o.findings.get(0);
                o.status = "found";
            } else {
                o.status = "clean";
            }
            return o;
        }
    }

    Crawler crawler(String... settings) {
        Properties props = new Properties();
        props.setProperty("crawl.dir", tmp.toString());
        for (String s : settings) props.setProperty(s.substring(0, s.indexOf('=')), s.substring(s.indexOf('=') + 1));
        return new Crawler(props);
    }

    @Test
    void newestFirstJudgingOnlyStandardLifeAndDeathAndWritingASuggestion() throws Exception {
        FakeSite site = new FakeSite();
        site.add(10, "life and death", true, false, 0);
        site.add(9, "tesuji", true, false, 0);
        site.add(8, "life and death", false, false, 0);
        site.add(7, "life and death", true, true, 0);
        site.add(6, "life and death", true, false, 1);
        site.add(4, "life and death", true, false, 0);
        FakeAnalyzer finder = new FakeAnalyzer();
        finder.finds.add("gp-4");
        crawler().crawl(site, finder);
        assertEquals(List.of("gp-10", "gp-4"), finder.judged);
        CrawlIndex index = CrawlIndex.load(tmp.resolve("problems.jsonl").toFile());
        assertEquals("clean", index.get(10).status);
        assertEquals("genre tesuji", index.get(9).reason);
        assertEquals("not marked standard", index.get(8).reason);
        assertEquals("in the sandbox", index.get(7).reason);
        assertEquals("deferred", index.get(6).status);
        assertEquals("no such problem", index.get(5).reason);
        assertEquals("found", index.get(4).status);
        assertEquals("18 kyu", index.get(4).difficulty);
        assertEquals(MissingVariationFinder.JUDGING_VERSION, index.get(4).version);
        SuggestionDraft d = RunRecorder.GSON.fromJson(Files.readString(tmp.resolve("suggestions/gp-4.json")), SuggestionDraft.class);
        assertEquals(4, d.problemId);
        assertEquals(3, d.addedNodes);
        assertTrue(Files.exists(tmp.resolve("findings/gp-4.json")));
        assertTrue(Files.readString(tmp.resolve("sgf/gp-4.sgf")).startsWith(SgfEditTest.GP62070));
        assertFalse(Files.exists(tmp.resolve("suggestions/gp-10.json")));
    }

    @Test
    void eachProblemJudgedIsHeadedByWhatTheRunHasCrawledSoFar() throws Exception {
        FakeSite site = new FakeSite();
        site.add(10, "life and death", true, false, 0);
        site.add(9, "tesuji", true, false, 0);
        site.add(8, "life and death", true, false, 1);
        site.add(6, "life and death", true, false, 0);
        site.add(5, "life and death", true, false, 0);
        FakeAnalyzer finder = new FakeAnalyzer();
        finder.finds.add("gp-6");
        Crawler c = crawler();
        c.clock = () -> 0;
        c.crawl(site, finder);
        // gp-7 is no problem on the site, skipped like the tesuji
        assertEquals(List.of("crawled so far this run: 0 in 0 s",
                "crawled so far this run: 4 in 0 s (1 clean, 1 deferred, 2 skipped)",
                "crawled so far this run: 5 in 0 s (1 found, 1 clean, 1 deferred, 2 skipped)"), finder.headings);
        // a later run counts from nothing again
        site.add(11, "life and death", true, false, 0);
        finder.headings.clear();
        c = crawler();
        c.clock = () -> 0;
        c.crawl(site, finder);
        assertEquals(List.of("crawled so far this run: 0 in 0 s"), finder.headings);
        // and a long run says how long in hours
        assertEquals("crawled so far this run: 290 in 26 h 5 min (3 found, 287 skipped)",
                Crawler.progress(290, Map.of("skipped", 287, "found", 3), 26 * 3600 + 5 * 60 + 59));
        assertEquals("47 min", Crawler.duration(47 * 60 + 30));
    }

    @Test
    void aLaterRunJudgesOnlyWhatIsNewOrWaitedAndStopsWhenAsked() throws Exception {
        FakeSite site = new FakeSite();
        for (int id = 1; id <= 5; id++) site.add(id, "life and death", true, false, 0);
        FakeAnalyzer finder = new FakeAnalyzer();
        crawler("crawl.max=2").crawl(site, finder);
        assertEquals(List.of("gp-5", "gp-4"), finder.judged);
        // two new problems on the site, and a stop: the new ones first, then on down
        site.add(6, "life and death", true, false, 0);
        site.add(7, "life and death", true, false, 0);
        finder.judged.clear();
        crawler("crawl.max=3").crawl(site, finder);
        assertEquals(List.of("gp-7", "gp-6", "gp-3"), finder.judged);
        finder.judged.clear();
        crawler().crawl(site, finder);
        assertEquals(List.of("gp-2", "gp-1"), finder.judged);
        finder.judged.clear();
        crawler().crawl(site, finder);
        assertEquals(List.of(), finder.judged);
    }

    @Test
    void aProblemChangedOnTheSiteIsJudgedAgainFirst() throws Exception {
        FakeSite site = new FakeSite();
        for (int id = 1; id <= 3; id++) site.add(id, "life and death", true, false, 0);
        FakeAnalyzer finder = new FakeAnalyzer();
        finder.finds.add("gp-2");
        crawler("crawl.max=2").crawl(site, finder);
        // its suggestion was reviewed, then the problem changed on the site
        Files.createDirectories(tmp.resolve("approvals"));
        Files.writeString(tmp.resolve("approvals/gp-2.json"), "{\"decision\":\"approved\"}");
        CrawlIndex.load(tmp.resolve("problems.jsonl").toFile()).append(CrawlIndexTest.entry(2, "stale", null, 0));
        finder.judged.clear();
        crawler().crawl(site, finder);
        assertEquals(List.of("gp-2", "gp-1"), finder.judged);
        // the review was of the old suggestion: it is kept aside, and the new one waits for one
        assertFalse(Files.exists(tmp.resolve("approvals/gp-2.json")));
        assertTrue(Files.exists(tmp.resolve("approvals/gp-2.old.json")));
        assertEquals("found", CrawlIndex.load(tmp.resolve("problems.jsonl").toFile()).get(2).status);
    }

    @Test
    void findingsNoSuggestionCanCarryAreKeptAndDraftedAgainLater() throws Exception {
        FakeSite site = new FakeSite();
        site.add(3, "life and death", true, false, 0);
        site.add(2, "life and death", true, false, 0);
        // the finder finds M9 at the start, a move the problem has as wrong, with no line on
        // through its refutation: no suggestion can carry it
        FakeAnalyzer finder = new FakeAnalyzer() {
            @Override
            public MissingVariationFinder.Outcome analyze(String name, String sgf, String difficulty, String heading) {
                MissingVariationFinder.Outcome o = super.analyze(name, sgf, difficulty, heading);
                if (name.equals("gp-3")) {
                    o.findings.add(SuggestionDraftTest.finding(List.of(), List.of("B S16"), "B S15", List.of("B S15")));
                    o.status = "found";
                }
                return o;
            }
        };
        crawler().crawl(site, finder);
        CrawlIndex index = CrawlIndex.load(tmp.resolve("problems.jsonl").toFile());
        assertEquals("unsuggested", index.get(3).status);
        assertTrue(index.get(3).reason.contains("on a wrong line"), index.get(3).reason);
        assertFalse(Files.exists(tmp.resolve("suggestions/gp-3.json")));
        // the drafting learns to go on past the refutation: its findings are drafted again, no
        // engine needed
        java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<MissingVariationFinder.Finding>>() {}.getType();
        List<MissingVariationFinder.Finding> fs = RunRecorder.GSON.fromJson(Files.readString(tmp.resolve("findings/gp-3.json")), type);
        fs.get(0).moves.get(0).lines.add(List.of("B S15", "W S16", "B R16"));
        Files.writeString(tmp.resolve("findings/gp-3.json"), RunRecorder.GSON.toJson(fs));
        finder.judged.clear();
        crawler().redraft();
        assertEquals(List.of(), finder.judged);
        assertEquals("found", CrawlIndex.load(tmp.resolve("problems.jsonl").toFile()).get(3).status);
        assertTrue(Files.exists(tmp.resolve("suggestions/gp-3.json")));
    }

    @Test
    void judgingAgainLeavesNothingOfTheEarlierJudgingBehind() throws Exception {
        FakeSite site = new FakeSite();
        site.add(1, "life and death", true, false, 0);
        FakeAnalyzer finder = new FakeAnalyzer();
        finder.finds.add("gp-1");
        crawler().crawl(site, finder);
        assertTrue(Files.exists(tmp.resolve("suggestions/gp-1.json")));
        Files.createDirectories(tmp.resolve("approvals"));
        Files.writeString(tmp.resolve("approvals/gp-1.json"), "{\"decision\":\"approved\"}");
        // judged by an older version, which found what the current one does not
        CrawlIndex.load(tmp.resolve("problems.jsonl").toFile()).append(CrawlIndexTest.entry(1, "found", MissingVariationFinder.JUDGING_VERSION - 1, 0));
        finder.finds.clear();
        crawler("crawl.recheck=found").crawl(site, finder);
        assertEquals("clean", CrawlIndex.load(tmp.resolve("problems.jsonl").toFile()).get(1).status);
        assertFalse(Files.exists(tmp.resolve("suggestions/gp-1.json")));
        assertFalse(Files.exists(tmp.resolve("findings/gp-1.json")));
        assertTrue(Files.exists(tmp.resolve("approvals/gp-1.old.json")));
        // so a redraft has nothing to bring back
        crawler().redraft();
        assertFalse(Files.exists(tmp.resolve("suggestions/gp-1.json")));
        assertEquals("clean", CrawlIndex.load(tmp.resolve("problems.jsonl").toFile()).get(1).status);
    }

    @Test
    void aProblemWhoseSuggestionWentToTheSiteIsLeftAlone() throws Exception {
        FakeSite site = new FakeSite();
        for (int id = 1; id <= 2; id++) site.add(id, "life and death", true, false, 0);
        Files.createDirectories(tmp.resolve("submitted"));
        Files.writeString(tmp.resolve("submitted/gp-2.json"), "{\"suggestionId\":1}");
        FakeAnalyzer finder = new FakeAnalyzer();
        crawler().crawl(site, finder);
        assertEquals(List.of("gp-1"), finder.judged);
        assertTrue(new File(tmp.toFile(), "state.json").exists());
    }
}
