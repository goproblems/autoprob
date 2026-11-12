package autoprob.analysis;

import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** One cancellable search. Restarting a session starts fresh visits, never sums old searches. */
final class LiveAnalysis {
    private final Properties props;

    LiveAnalysis(Properties props) { this.props = props; }

    JsonObject analyze(AnalysisTask task, AnalysisKataBrain brain, ReviewAnalysis.Progress progress) throws Exception {
        KataQuery query = new Gson().fromJson(task.payload, KataQuery.class);
        int size = task.payload.get("boardSize").getAsInt();
        int turn = query.moves.size();
        query.boardXSize = size;
        query.boardYSize = size;
        query.analyzeTurns = List.of(turn);
        query.id = "live:" + task.id + ":" + UUID.randomUUID() + ":";
        query.includeOwnership = true;
        query.includePolicy = false;
        query.includeMovesOwnership = false;
        query.maxVisits = Integer.parseInt(props.getProperty("live.max_visits"));
        query.reportDuringSearchEvery = Double.parseDouble(props.getProperty("live.report_interval_seconds"));
        query.overrideSettings = new KataQuery.OverrideSettings();
        query.overrideSettings.ignorePreRootHistory = false;
        query.overrideSettings.humanSLProfile = "";
        if (!props.getProperty("kata.human_model", "").isBlank()) {
            query.overrideSettings.humanSLRootExploreProbWeightless = 0.0;
            query.overrideSettings.humanSLRootExploreProbWeightful = 0.0;
            query.overrideSettings.humanSLPlaExploreProbWeightful = 0.0;
            query.overrideSettings.humanSLOppExploreProbWeightful = 0.0;
        }
        long deadline = Instant.parse(task.payload.get("expiresAt").getAsString()).toEpochMilli();
        query.overrideSettings.maxTime = Math.max(0.1, (deadline - System.currentTimeMillis()) / 1000.0);
        ReviewAnalysis normalizer = new ReviewAnalysis(props);
        BlockingQueue<KataAnalysisResult> updates = brain.streamQuery(query);
        int sequence = 0;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Live analysis stopped");
            KataAnalysisResult result = updates.poll(1, TimeUnit.SECONDS);
            if (result == null) {
                if (!brain.isAlive()) throw new IllegalStateException("KataGo exited during live analysis");
                if (System.currentTimeMillis() > deadline + 10000) throw new IllegalStateException("Live analysis exceeded its time limit");
                continue;
            }
            if (result.isError() || result.rootInfo == null) throw new IllegalStateException("KataGo: " + result.error);
            JsonObject snapshot = normalizer.envelope(List.of(normalizer.normalize(result, turn, size)), brain);
            snapshot.addProperty("generation", task.generation);
            snapshot.addProperty("sequence", ++sequence);
            snapshot.addProperty("isDuringSearch", Boolean.TRUE.equals(result.isDuringSearch));
            if (!Boolean.TRUE.equals(result.isDuringSearch)) return snapshot;
            progress.accept(snapshot);
        }
    }
}
