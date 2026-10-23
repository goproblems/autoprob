package autoprob.validate.cases;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import autoprob.validate.CandidateFeatures;
import autoprob.validate.DecisionRecord;
import autoprob.validate.IsolationRecord;
import autoprob.validate.RunRecorder;

class CaseStoreTest {
    @TempDir
    Path dir;

    private static IsolationRecord isolation(double komi) {
        IsolationRecord iso = new IsolationRecord();
        iso.solver = "B";
        iso.komi = komi;
        iso.targetLead = 2.5;
        iso.stakes.addAll(List.of("A1", "B2"));
        for (int y = 0; y < 19; y++) iso.rows.add(".".repeat(19));
        return iso;
    }

    private static DecisionCase kase(String move, double komi) {
        DecisionCase c = new DecisionCase();
        c.at.add(move);
        c.toMove = "W";
        c.role = DecisionRecord.OPPONENT;
        c.expect.choose.add("W C3");
        c.isolation = isolation(komi);
        c.observed = new DecisionCase.Observed();
        c.observed.run = "run";
        c.observed.decision = new DecisionRecord();
        CandidateFeatures cand = new CandidateFeatures();
        cand.move = "W C3";
        cand.source = "katago";
        cand.prior = 0.971966684;
        cand.visits = 989;
        Map<String, Double> deltas = new LinkedHashMap<>();
        deltas.put("A1", 0.0);
        deltas.put("B2", 0.0001);
        deltas.put("C3", -1.23456);
        cand.deltas = deltas;
        c.observed.decision.candidates.add(cand);
        return c;
    }

    private static CaseFile caseFile() {
        CaseFile cf = new CaseFile();
        cf.problem = "gp-1";
        cf.acceptedLines.add(List.of("B A1", "W C3"));
        cf.decisions.add(kase("B A1", 12));
        cf.decisions.add(kase("B B1", 12));
        cf.decisions.add(kase("B C1", 10.5)); // recorded on a run that isolated differently
        return cf;
    }

    @Test
    void writesCompactlyAndReadsBack() throws Exception {
        CaseStore store = new CaseStore(dir.toFile());
        store.save(caseFile());
        String text = Files.readString(dir.resolve("gp-1.json"));

        // the shared isolation once for the file, a differing one kept on its case
        assertEquals(2, text.split("\"isolation\"", -1).length - 1);
        // plain lists on one line, but the board one row per line
        assertTrue(text.contains("\"stakes\": [\"A1\", \"B2\"]"), text);
        assertTrue(text.contains("\"rows\": [\n"), text);
        assertTrue(text.contains("\"acceptedLines\": [\n    [\"B A1\", \"W C3\"]\n  ]"), text);
        // rounded numbers, whole numbers as they are, deltas of 0 left out
        assertTrue(text.contains("\"prior\": 0.972"), text);
        assertTrue(text.contains("\"visits\": 989"), text);
        assertTrue(text.contains("\"deltas\": {\"C3\": -1.235}"), text);

        CaseFile back = store.load("gp-1");
        assertEquals(12.0, back.decisions.get(0).isolation.komi);
        assertEquals(12.0, back.decisions.get(1).isolation.komi);
        assertEquals(10.5, back.decisions.get(2).isolation.komi);
        assertEquals(19, back.decisions.get(0).isolation.rows.size());
        CandidateFeatures cand = back.decisions.get(0).observed.decision.candidates.get(0);
        assertEquals(0.972, cand.prior);
        assertEquals(Map.of("C3", -1.235), cand.deltas);

        // saving what was read writes the same file again
        store.save(back);
        assertEquals(text, Files.readString(dir.resolve("gp-1.json")));
    }

    @Test
    void readsTheOlderFormat() throws Exception {
        // before: gson pretty printing, an isolation in every case, nothing at the top
        File f = dir.resolve("gp-1.json").toFile();
        Files.writeString(f.toPath(), RunRecorder.GSON.toJson(caseFile()));
        CaseFile cf = new CaseStore(dir.toFile()).load("gp-1");
        assertEquals(12.0, cf.decisions.get(0).isolation.komi);
        assertEquals(10.5, cf.decisions.get(2).isolation.komi);
        assertNotSame(cf.decisions.get(0).isolation, cf.decisions.get(1).isolation);
        assertEquals(0.971966684, cf.decisions.get(0).observed.decision.candidates.get(0).prior);
    }
}
