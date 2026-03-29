package autoprob.scenario;

import autoprob.api.ScenarioMetadata;

import java.awt.Point;
import java.util.Map;
import java.util.Properties;

/**
 * Holds all configurable analysis parameters.
 * Loaded from {@link Properties} defaults, then optionally overridden per-scenario
 * via {@link ScenarioMetadata#configOverrides}.
 *
 * <p>This class is NOT thread-safe. A new instance should be created per analysis request
 * when overrides are present.</p>
 */
public class AnalysisConfig {

    // --- Overridable parameters (from configOverrides spec) ---
    public double minHumanPolicy;
    public boolean includeOptimalMoves;
    public int minDepthForEndness;
    public double scoreDropThreshold;
    public double maxEndness;
    public double minEndness;
    public double ownershipThreshold;
    public double depthTargetMoves;
    public double depthPower;
    public int tenukiHistoryMoves;
    public double tenukiDistanceThreshold;
    public int maxSenteCandidates;
    public double minSentePolicy;
    public double minUrgencyToContinue;
    public double maxScoreDropMaxMode;
    public int minResponseVisitsMaxMode;

    // --- Non-overridable parameters (not exposed to problem creators) ---
    public int maxOptimalMoves;
    public int passMoveVisits;
    public int precalculationMaxDepth;
    public int precalculationMaxNodes;
    public int precalculationBatchSize;
    public boolean precalculationDepthFirst;
    public double precalculationMinPolicy;

    // --- Scenario metadata (area constraints, target positions, etc.) ---
    /** The scenario metadata, or null when using defaults only. */
    public ScenarioMetadata metadata;

    /**
     * Create an AnalysisConfig from the default properties file.
     */
    public static AnalysisConfig fromProperties(Properties props) {
        AnalysisConfig c = new AnalysisConfig();
        c.minHumanPolicy = Double.parseDouble(props.getProperty("scenario.min_response_policy", "0.05"));
        c.includeOptimalMoves = Boolean.parseBoolean(props.getProperty("scenario.include_optimal_moves", "false"));
        c.minDepthForEndness = Integer.parseInt(props.getProperty("scenario.min_depth_for_endness", "5"));
        c.scoreDropThreshold = Double.parseDouble(props.getProperty("scenario.score_drop_threshold", "15.0"));
        c.maxEndness = Double.parseDouble(props.getProperty("scenario.max_endness", "1.0"));
        c.minEndness = Double.parseDouble(props.getProperty("scenario.min_endness", "-1.0"));
        c.ownershipThreshold = Double.parseDouble(props.getProperty("scenario.ownership_threshold", "0.6"));
        c.depthTargetMoves = Double.parseDouble(props.getProperty("scenario.depth_target_moves", "30.0"));
        c.depthPower = Double.parseDouble(props.getProperty("scenario.depth_power", "1.1"));
        c.tenukiHistoryMoves = Integer.parseInt(props.getProperty("scenario.tenuki_history_moves", "3"));
        c.tenukiDistanceThreshold = Double.parseDouble(props.getProperty("scenario.tenuki_distance_threshold", "6.0"));
        c.maxOptimalMoves = Integer.parseInt(props.getProperty("scenario.max_optimal_moves", "1"));
        c.maxSenteCandidates = Integer.parseInt(props.getProperty("scenario.max_sente_candidates", "5"));
        c.minSentePolicy = Double.parseDouble(props.getProperty("scenario.min_sente_policy", "0.05"));
        c.minUrgencyToContinue = Double.parseDouble(props.getProperty("scenario.min_urgency_to_continue", "10.0"));
        c.passMoveVisits = Integer.parseInt(props.getProperty("scenario.pass_move_visits", "200"));
        c.precalculationMaxDepth = Integer.parseInt(props.getProperty("scenario.precalculation_max_depth", "20"));
        c.precalculationMaxNodes = Integer.parseInt(props.getProperty("scenario.precalculation_max_nodes", "3000"));
        c.precalculationBatchSize = Integer.parseInt(props.getProperty("scenario.precalculation_batch_size", "10"));
        c.precalculationDepthFirst = props.getProperty("scenario.precalculation_strategy", "bfs").equalsIgnoreCase("dfs");
        c.precalculationMinPolicy = Double.parseDouble(props.getProperty("scenario.precalculation_min_policy", "0.1"));
        c.maxScoreDropMaxMode = Double.parseDouble(props.getProperty("scenario.max_score_drop_max_mode", "0.5"));
        c.minResponseVisitsMaxMode = Integer.parseInt(props.getProperty("scenario.min_response_visits_max_mode", "50"));
        return c;
    }

    /**
     * Create a copy with metadata reference and config overrides applied.
     */
    public AnalysisConfig withMetadata(ScenarioMetadata metadata) {
        AnalysisConfig c = this.copy();
        if (metadata == null) {
            return c;
        }

        c.metadata = metadata;

        // Apply config overrides
        if (metadata.configOverrides != null && !metadata.configOverrides.isEmpty()) {
            applyOverrides(c, metadata.configOverrides);
        }

        return c;
    }

    /** Checks if the player can move at this point. */
    public boolean isPlayerMoveAllowed(Point p) {
        return metadata == null || metadata.isPlayerMoveAllowed(p);
    }

    /** Checks if the computer can respond at this point. */
    public boolean isComputerMoveAllowed(Point p) {
        return metadata == null || metadata.isComputerMoveAllowed(p);
    }

    public boolean hasComputerAreaConstraints() {
        return metadata != null && metadata.hasComputerAreaConstraints();
    }

    public boolean hasPlayerAreaConstraints() {
        return metadata != null && metadata.hasPlayerAreaConstraints();
    }

    private AnalysisConfig copy() {
        AnalysisConfig c = new AnalysisConfig();
        c.minHumanPolicy = this.minHumanPolicy;
        c.includeOptimalMoves = this.includeOptimalMoves;
        c.minDepthForEndness = this.minDepthForEndness;
        c.scoreDropThreshold = this.scoreDropThreshold;
        c.maxEndness = this.maxEndness;
        c.minEndness = this.minEndness;
        c.ownershipThreshold = this.ownershipThreshold;
        c.depthTargetMoves = this.depthTargetMoves;
        c.depthPower = this.depthPower;
        c.tenukiHistoryMoves = this.tenukiHistoryMoves;
        c.tenukiDistanceThreshold = this.tenukiDistanceThreshold;
        c.maxOptimalMoves = this.maxOptimalMoves;
        c.maxSenteCandidates = this.maxSenteCandidates;
        c.minSentePolicy = this.minSentePolicy;
        c.minUrgencyToContinue = this.minUrgencyToContinue;
        c.passMoveVisits = this.passMoveVisits;
        c.precalculationMaxDepth = this.precalculationMaxDepth;
        c.precalculationMaxNodes = this.precalculationMaxNodes;
        c.precalculationBatchSize = this.precalculationBatchSize;
        c.precalculationDepthFirst = this.precalculationDepthFirst;
        c.precalculationMinPolicy = this.precalculationMinPolicy;
        c.maxScoreDropMaxMode = this.maxScoreDropMaxMode;
        c.minResponseVisitsMaxMode = this.minResponseVisitsMaxMode;
        c.metadata = this.metadata;
        return c;
    }

    private static void applyOverrides(AnalysisConfig c, Map<String, Object> overrides) {
        for (Map.Entry<String, Object> entry : overrides.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) continue;

            switch (key) {
                case "scenario.analysis.visits":
                    // Visits are handled separately via determineVisits() in Analysis
                    // This override is applied there by checking metadata directly
                    break;
                case "scenario.min_response_policy":
                    c.minHumanPolicy = toDouble(value);
                    break;
                case "scenario.include_optimal_moves":
                    c.includeOptimalMoves = toBoolean(value);
                    break;
                case "scenario.min_depth_for_endness":
                    c.minDepthForEndness = toInt(value);
                    break;
                case "scenario.score_drop_threshold":
                    c.scoreDropThreshold = toDouble(value);
                    break;
                case "scenario.max_endness":
                    c.maxEndness = toDouble(value);
                    break;
                case "scenario.min_endness":
                    c.minEndness = toDouble(value);
                    break;
                case "scenario.depth_target_moves":
                    c.depthTargetMoves = toDouble(value);
                    break;
                case "scenario.depth_power":
                    c.depthPower = toDouble(value);
                    break;
                case "scenario.tenuki_history_moves":
                    c.tenukiHistoryMoves = toInt(value);
                    break;
                case "scenario.tenuki_distance_threshold":
                    c.tenukiDistanceThreshold = toDouble(value);
                    break;
                case "scenario.max_sente_candidates":
                    c.maxSenteCandidates = toInt(value);
                    break;
                case "scenario.min_sente_policy":
                    c.minSentePolicy = toDouble(value);
                    break;
                case "scenario.min_urgency_to_continue":
                    c.minUrgencyToContinue = toDouble(value);
                    break;
                case "scenario.ownership_threshold":
                    c.ownershipThreshold = toDouble(value);
                    break;
                case "scenario.max_score_drop_max_mode":
                    c.maxScoreDropMaxMode = toDouble(value);
                    break;
                case "scenario.min_response_visits_max_mode":
                    c.minResponseVisitsMaxMode = toInt(value);
                    break;
                default:
                    System.out.println("Unknown config override key: " + key);
                    break;
            }
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        return Double.parseDouble(value.toString());
    }

    private static int toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        return Integer.parseInt(value.toString());
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        return Boolean.parseBoolean(value.toString());
    }

}
