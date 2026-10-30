package autoprob.analysis;

import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;
import autoprob.katastruct.MoveInfo;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/** Full game analysis from the server's normalized SGF, including both endpoints. */
public class ReviewAnalysis {
    @FunctionalInterface
    public interface Progress { void accept(JsonObject result) throws Exception; }
    @FunctionalInterface
    public interface YieldCheck { boolean shouldYield() throws Exception; }
    public static class YieldRequested extends Exception { }
    private final Gson gson = new Gson();
    private final Properties props;

    public ReviewAnalysis(Properties props) { this.props = props; }

    public JsonObject analyze(AnalysisTask task, AnalysisKataBrain brain, Progress progress,
                              YieldCheck shouldYield) throws Exception {
        JsonObject payload = task.payload;
        KataQuery query = gson.fromJson(payload, KataQuery.class);
        if (!payload.has("boardSize") || query.moves == null || query.initialStones == null
            || query.komi == null || !payload.has("rules") || !payload.has("initialPlayer")) {
            throw new IllegalArgumentException("Review requires normalized boardSize, moves, initialStones, komi, rules, initialPlayer");
        }
        int boardSize = payload.get("boardSize").getAsInt();
        if (boardSize < 2 || boardSize > 19 || query.moves.size() > 1000) {
            throw new IllegalArgumentException("Unsupported board size or game length");
        }
        query.boardXSize = boardSize;
        query.boardYSize = boardSize;
        query.includeOwnership = true;
        query.includeMovesOwnership = false;
        query.includeOwnershipStdev = false;
        query.maxVisits = query.maxVisits == null
            ? Integer.parseInt(props.getProperty("review.visits", "500")) : query.maxVisits;
        if (query.maxVisits < 1) throw new IllegalArgumentException("maxVisits must be positive");
        // Never inherit human-play biases from a scenario task.
        query.overrideSettings = new KataQuery.OverrideSettings();
        query.overrideSettings.ignorePreRootHistory = false;
        query.overrideSettings.humanSLProfile = "";
        if (!props.getProperty("kata.human_model", "").isBlank()) {
            query.overrideSettings.humanSLRootExploreProbWeightless = 0.0;
            query.overrideSettings.humanSLRootExploreProbWeightful = 0.0;
            query.overrideSettings.humanSLPlaExploreProbWeightful = 0.0;
            query.overrideSettings.humanSLOppExploreProbWeightful = 0.0;
        }
        Map<Integer, JsonObject> positions = new HashMap<>();
        if (task.result != null && "black".equals(string(task.result, "perspective"))
            && new File(brain.modelPath).getName().equals(string(task.result, "model"))
            && task.result.has("positions")) {
            for (JsonElement prior : task.result.getAsJsonArray("positions")) {
                JsonObject position = prior.getAsJsonObject();
                positions.put(position.get("turnNumber").getAsInt(), position);
            }
        }
        long queryTimeoutMs = Math.max(1000, Long.parseLong(props.getProperty("review.query.timeout.ms", "120000")));
        for (int turn = 0; turn <= query.moves.size(); turn++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Review interrupted");
            if (shouldYield.shouldYield()) throw new YieldRequested();
            if (positions.containsKey(turn)) continue;
            // A single position per query permits yielding to live scenario jobs between moves.
            // Full game history remains intact for ko, pass, handicap and rules handling.
            query.id = "review:" + task.id + ":" + UUID.randomUUID() + ":";
            query.analyzeTurns = List.of(turn);
            brain.doQuery(query);
            KataAnalysisResult result = brain.getResult(query.id, turn, queryTimeoutMs);
            if (result == null || result.isError() || result.rootInfo == null) {
                throw new IllegalStateException("KataGo failed at turn " + turn + ": "
                    + (result == null ? "missing result" : result.error));
            }
            JsonObject position = normalize(result, turn, boardSize);
            positions.put(turn, position);
            progress.accept(envelope(List.of(position), brain));
        }
        // Every position has already been durably uploaded; the server merges and validates completion.
        return envelope(List.of(), brain);
    }

    private JsonObject normalize(KataAnalysisResult result, int turn, int boardSize) {
        if (result.turnNumber == null || result.turnNumber != turn
            || !("B".equals(result.rootInfo.currentPlayer) || "W".equals(result.rootInfo.currentPlayer))) {
            throw new IllegalStateException("KataGo returned invalid position metadata");
        }
        JsonObject position = new JsonObject();
        position.addProperty("turnNumber", turn);
        position.addProperty("currentPlayer", result.rootInfo.currentPlayer);
        position.addProperty("winrate", probability(result.rootInfo.winrate));
        position.addProperty("scoreLead", finite(result.rootInfo.scoreLead));
        position.addProperty("visits", visits(result.rootInfo.visits));
        JsonArray moves = new JsonArray();
        List<MoveInfo> candidates = result.moveInfos == null ? new ArrayList<>() : new ArrayList<>(result.moveInfos);
        candidates.sort(Comparator.comparingInt(move -> move.order == null ? Integer.MAX_VALUE : move.order));
        for (MoveInfo move : candidates.subList(0, Math.min(10, candidates.size()))) {
            if (move.move == null || move.move.isBlank()) throw new IllegalStateException("Missing candidate move");
            JsonObject item = new JsonObject();
            item.addProperty("move", move.move);
            item.addProperty("winrate", probability(move.winrate));
            item.addProperty("scoreLead", finite(move.scoreLead));
            item.addProperty("visits", visits(move.visits));
            item.add("pv", gson.toJsonTree(move.pv == null ? List.of() : move.pv.subList(0, Math.min(30, move.pv.size()))));
            if (move.order != null) item.addProperty("order", move.order);
            moves.add(item);
        }
        position.add("moveInfos", moves);
        if (result.ownership != null) {
            if (result.ownership.size() != boardSize * boardSize) throw new IllegalStateException("Invalid ownership size");
            for (Double ownership : result.ownership) {
                if (finite(ownership) < -1 || ownership > 1) throw new IllegalStateException("Invalid ownership value");
            }
            position.add("ownership", gson.toJsonTree(result.ownership));
        }
        return position;
    }

    private JsonObject envelope(List<JsonObject> positions, AnalysisKataBrain brain) {
        JsonObject result = new JsonObject();
        result.addProperty("perspective", "black");
        result.addProperty("model", new File(brain.modelPath).getName());
        result.add("positions", gson.toJsonTree(positions));
        return result;
    }
    private static double finite(Double value) {
        if (value == null || !Double.isFinite(value)) throw new IllegalStateException("Invalid KataGo numeric value");
        return value;
    }
    private static double probability(Double value) {
        double number = finite(value);
        if (number < 0 || number > 1) throw new IllegalStateException("Invalid KataGo winrate");
        return number;
    }
    private static int visits(Integer value) {
        if (value == null || value < 0) throw new IllegalStateException("Invalid KataGo visits");
        return value;
    }
    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : null;
    }
}
