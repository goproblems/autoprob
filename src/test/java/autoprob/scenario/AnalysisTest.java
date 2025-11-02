package autoprob.scenario;

import autoprob.NodeAnalyzer;
import autoprob.api.AnalysisRequest;
import autoprob.api.AnalysisResult;
import autoprob.api.AnalysisTestFixtures;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;
import autoprob.katastruct.RootInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalysisTest {

    @Test
    void analyzeRequestComputesScoreAndLossFromKataResults() throws Exception {
        AnalysisRequest request = AnalysisTestFixtures.loadRequest("scenario/sample-analysis-request.json");

        Properties props = new Properties();
        props.setProperty("scenario.analysis.visits", "512");

        KataAnalysisResult rootResult = kataResult(
                2.45,
                1200,
                List.of(moveInfo("F5", 2.30, 800, 0.9))
        );
        KataAnalysisResult playResult = kataResult(
                2.30,
                950,
                List.of()
        );

        FakeNodeAnalyzer analyzer = new FakeNodeAnalyzer(rootResult, playResult);

        try (Analysis analysis = new Analysis(props, null, analyzer)) {
            AnalysisResult result = analysis.analyze(request);

            assertNotNull(result.score);
            assertNotNull(result.loss);
            assertRange(result.score, 2.20, 2.40);
            assertRange(result.loss, -0.20, -0.10);
            assertEquals(request.path, result.path);
        }
    }

    private static KataAnalysisResult kataResult(double scoreLead, int visits, List<MoveInfo> moves) {
        KataAnalysisResult result = new KataAnalysisResult();
        RootInfo info = new RootInfo();
        info.scoreLead = scoreLead;
        info.visits = visits;
        result.rootInfo = info;
        result.moveInfos = new ArrayList<>(moves);
        return result;
    }

    private static MoveInfo moveInfo(String move, double scoreLead, int visits, double weight) {
        MoveInfo info = new MoveInfo();
        info.move = move;
        info.scoreLead = scoreLead;
        info.visits = visits;
        info.weight = weight;
        info.prior = 0.2;
        return info;
    }

    private static void assertRange(double value, double min, double max) {
        assertTrue(value >= min, () -> "Expected >= " + min + " but was " + value);
        assertTrue(value <= max, () -> "Expected <= " + max + " but was " + value);
    }

    private static final class FakeNodeAnalyzer extends NodeAnalyzer {
        private final ArrayDeque<KataAnalysisResult> responses;

        FakeNodeAnalyzer(KataAnalysisResult... results) {
            super(new Properties());
            this.responses = new ArrayDeque<>(Arrays.asList(results));
        }

        @Override
        public KataAnalysisResult analyzeNode(autoprob.KataBrain brain, Node node, int visits,
                                              ArrayList<String> moves, String humanSLrank) {
            if (responses.isEmpty()) {
                throw new IllegalStateException("No more stub Kata results available");
            }
            return responses.removeFirst();
        }
    }
}
