package autoprob.joseki;

import autoprob.ApiClient;
import autoprob.api.JosekiNodeCompletionCandidateData;
import autoprob.api.JosekiNodeCompletionCandidatePage;
import com.google.gson.Gson;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class JosekiNodeCompleter {
    private static final double DEFAULT_AI_POLICY_THRESHOLD = 0.10;
    private static final double DEFAULT_HUMAN_POLICY_THRESHOLD = 0.20;
    private static final int DEFAULT_MAX_MOVE_DISTANCE = 4;
    private static final int DEFAULT_MAX_PATH_DEPTH = 30;
    private static final int DEFAULT_PAGE_LIMIT = 100;
    private static final DecimalFormat POLICY_FORMAT = new DecimalFormat("0.0000");

    private final Properties props;
    private final ApiClient apiClient;
    private final Gson gson = new Gson();

    public record CompletionSummary(
        int scannedNodes,
        int prunedNodes,
        int skippedNodes,
        int discoveredCandidates,
        int createdRequests,
        int pendingRequests,
        boolean dryRun,
        int snapshotMaxNodeId
    ) {}

    public JosekiNodeCompleter(Properties props) {
        this(props, new ApiClient());
    }

    JosekiNodeCompleter(Properties props, ApiClient apiClient) {
        this.props = props;
        this.apiClient = apiClient;
    }

    public CompletionSummary run() throws Exception {
        double aiPolicyThreshold = policyThreshold(
            "joseki.completion.ai_policy_threshold",
            DEFAULT_AI_POLICY_THRESHOLD
        );
        double humanPolicyThreshold = policyThreshold(
            "joseki.completion.human_policy_threshold",
            DEFAULT_HUMAN_POLICY_THRESHOLD
        );
        int maxMoveDistance = rangedInt(
            "joseki.completion.max_move_distance",
            props.getProperty(
                "joseki.max_move_distance",
                String.valueOf(DEFAULT_MAX_MOVE_DISTANCE)
            ),
            0,
            18
        );
        int pageLimit = rangedInt(
            "joseki.completion.page_limit",
            String.valueOf(DEFAULT_PAGE_LIMIT),
            1,
            500
        );
        int maxPathDepth = rangedInt(
            "joseki.completion.max_path_depth",
            String.valueOf(DEFAULT_MAX_PATH_DEPTH),
            1,
            500
        );
        boolean dryRun = Boolean.parseBoolean(props.getProperty("joseki.completion.dry_run", "false"));
        boolean pruneLowPolicyPaths = Boolean.parseBoolean(
            props.getProperty("joseki.completion.prune_low_policy_paths", "true")
        );
        boolean printCandidates = Boolean.parseBoolean(
            props.getProperty("joseki.completion.print_candidates", "false")
        );

        System.out.println("Scanning one fixed joseki node snapshot"
            + " (AI policy >= " + POLICY_FORMAT.format(aiPolicyThreshold)
            + ", Human Policy >= " + POLICY_FORMAT.format(humanPolicyThreshold)
            + ", max move distance=" + maxMoveDistance
            + ", max path depth=" + maxPathDepth
            + ", page limit=" + pageLimit
            + ", prune low-policy paths=" + pruneLowPolicyPaths
            + (dryRun ? ", dry run" : "") + ")");

        long startedAt = System.currentTimeMillis();
        int afterNodeId = 0;
        Integer snapshotMaxNodeId = null;
        int scannedNodes = 0;
        int prunedNodes = 0;
        int skippedNodes = 0;
        int discoveredCandidates = 0;
        int createdRequests = 0;
        int pendingRequests = 0;
        Set<String> seenPaths = new HashSet<>();

        while (true) {
            JosekiNodeCompletionCandidatePage page = fetchCandidatePage(
                afterNodeId,
                snapshotMaxNodeId,
                pageLimit,
                aiPolicyThreshold,
                humanPolicyThreshold,
                maxMoveDistance,
                maxPathDepth,
                pruneLowPolicyPaths
            );
            if (snapshotMaxNodeId == null) {
                snapshotMaxNodeId = page.snapshotMaxNodeId;
                System.out.println("Completion snapshot ends at joseki node id " + snapshotMaxNodeId);
            } else if (snapshotMaxNodeId != page.snapshotMaxNodeId) {
                throw new IllegalStateException("Joseki completion snapshot changed while scanning");
            }

            scannedNodes += page.scannedNodes;
            prunedNodes += page.prunedNodes;
            skippedNodes += page.skippedNodes;
            List<JosekiNodeCompletionCandidateData> entries = page.entries == null
                ? List.of()
                : page.entries;
            for (JosekiNodeCompletionCandidateData candidate : entries) {
                if (candidate == null || candidate.path == null || candidate.path.isBlank()) {
                    throw new IllegalStateException("Joseki completion API returned a candidate without a path");
                }
                if (!seenPaths.add(candidate.path)) {
                    continue;
                }

                discoveredCandidates++;
                if (dryRun) {
                    if (printCandidates) {
                        printCandidate("Would request", candidate);
                    }
                    continue;
                }

                ApiClient.ApiResponse<Object> response = createAnalysisRequest(candidate.path);
                if (response.getStatusCode() == 409) {
                    pendingRequests++;
                    if (printCandidates) {
                        printCandidate("Already pending", candidate);
                    }
                } else if (response.isSuccess()) {
                    createdRequests++;
                    if (printCandidates) {
                        printCandidate("Requested", candidate);
                    }
                } else {
                    throw new RuntimeException("Failed to create joseki analysis request for path "
                        + candidate.path + ": HTTP " + response.getStatusCode()
                        + " - " + response.getErrorMessage());
                }
            }

            printProgress(
                page.afterNodeId,
                snapshotMaxNodeId,
                scannedNodes,
                prunedNodes,
                skippedNodes,
                discoveredCandidates,
                createdRequests,
                pendingRequests,
                dryRun,
                startedAt
            );

            if (!page.hasMore) {
                break;
            }
            if (page.afterNodeId <= afterNodeId) {
                throw new IllegalStateException("Joseki completion API did not advance its node cursor");
            }
            afterNodeId = page.afterNodeId;
        }

        CompletionSummary summary = new CompletionSummary(
            scannedNodes,
            prunedNodes,
            skippedNodes,
            discoveredCandidates,
            createdRequests,
            pendingRequests,
            dryRun,
            snapshotMaxNodeId == null ? 0 : snapshotMaxNodeId
        );
        System.out.println("Joseki node completion " + (dryRun ? "estimate" : "complete")
            + ": scanned " + scannedNodes
            + " nodes, pruned " + prunedNodes
            + ", skipped " + skippedNodes
            + ", found " + discoveredCandidates + " missing high-policy nodes"
            + (dryRun ? "." : ", created " + createdRequests
                + " analysis requests, already pending " + pendingRequests + "."));
        return summary;
    }

    private JosekiNodeCompletionCandidatePage fetchCandidatePage(
        int afterNodeId,
        Integer snapshotMaxNodeId,
        int pageLimit,
        double aiPolicyThreshold,
        double humanPolicyThreshold,
        int maxMoveDistance,
        int maxPathDepth,
        boolean pruneLowPolicyPaths
    ) throws Exception {
        List<String> params = new ArrayList<>();
        addQueryParam(params, "afterNodeId", String.valueOf(afterNodeId));
        if (snapshotMaxNodeId != null) {
            addQueryParam(params, "maxNodeId", String.valueOf(snapshotMaxNodeId));
        }
        addQueryParam(params, "limit", String.valueOf(pageLimit));
        addQueryParam(params, "aiPolicyThreshold", String.valueOf(aiPolicyThreshold));
        addQueryParam(params, "humanPolicyThreshold", String.valueOf(humanPolicyThreshold));
        addQueryParam(params, "maxMoveDistance", String.valueOf(maxMoveDistance));
        addQueryParam(params, "maxPathDepth", String.valueOf(maxPathDepth));
        addQueryParam(params, "pruneLowPolicyPaths", String.valueOf(pruneLowPolicyPaths));

        ApiClient.ApiResponse<JosekiNodeCompletionCandidatePage> response = apiClient.makeGetRequest(
            "api.joseki.node_completion_candidates",
            null,
            "?" + String.join("&", params),
            JosekiNodeCompletionCandidatePage.class,
            props
        );
        if (!response.isSuccess() || response.getData() == null) {
            throw new RuntimeException("Failed to fetch joseki node completion candidates: HTTP "
                + response.getStatusCode() + " - " + response.getErrorMessage());
        }
        return response.getData();
    }

    private ApiClient.ApiResponse<Object> createAnalysisRequest(String path) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("path", path);
        body.put("source", "precalculate");
        return apiClient.makePostRequest(
            "api.joseki.analysis_requests",
            null,
            gson.toJson(body),
            Object.class,
            props
        );
    }

    private void printCandidate(String action, JosekiNodeCompletionCandidateData candidate) {
        String ai = candidate.aiPolicy == null ? "-" : POLICY_FORMAT.format(candidate.aiPolicy);
        String human = candidate.maxHumanPolicy == null
            ? "-"
            : POLICY_FORMAT.format(candidate.maxHumanPolicy)
                + (candidate.humanPolicyProfile == null ? "" : "@" + candidate.humanPolicyProfile);
        System.out.println(action + " path=" + candidate.path
            + " parent=" + (candidate.parentPath == null || candidate.parentPath.isBlank()
                ? "<root>"
                : candidate.parentPath)
            + " ai=" + ai + " human=" + human);
    }

    private void printProgress(
        int cursorNodeId,
        int snapshotMaxNodeId,
        int scannedNodes,
        int prunedNodes,
        int skippedNodes,
        int discoveredCandidates,
        int createdRequests,
        int pendingRequests,
        boolean dryRun,
        long startedAt
    ) {
        double progress = snapshotMaxNodeId <= 0
            ? 1.0
            : Math.min(1.0, Math.max(0.0, cursorNodeId / (double) snapshotMaxNodeId));
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - startedAt);
        long remainingMs = progress <= 0.0
            ? -1L
            : Math.max(0L, Math.round(elapsedMs * (1.0 - progress) / progress));

        StringBuilder message = new StringBuilder("Completion progress: cursor ")
            .append(cursorNodeId)
            .append("/")
            .append(snapshotMaxNodeId)
            .append(" (")
            .append(POLICY_FORMAT.format(progress * 100.0))
            .append("%), scanned=")
            .append(scannedNodes)
            .append(", pruned=")
            .append(prunedNodes)
            .append(", skipped=")
            .append(skippedNodes)
            .append(", candidates=")
            .append(discoveredCandidates);
        if (!dryRun) {
            message.append(", requested=")
                .append(createdRequests)
                .append(", pending=")
                .append(pendingRequests);
        }
        message.append(", elapsed=").append(formatDuration(elapsedMs));
        if (remainingMs >= 0L && progress < 1.0) {
            message.append(", ETA~").append(formatDuration(remainingMs));
        }
        System.out.println(message);
    }

    private String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return hours + "h" + minutes + "m";
        }
        if (minutes > 0L) {
            return minutes + "m" + seconds + "s";
        }
        return seconds + "s";
    }

    private double policyThreshold(String property, double defaultValue) {
        double value = Double.parseDouble(props.getProperty(property, String.valueOf(defaultValue)));
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(property + " must be between 0 and 1");
        }
        return value;
    }

    private int rangedInt(String property, String defaultValue, int minimum, int maximum) {
        int value = Integer.parseInt(props.getProperty(property, defaultValue));
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(property + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private void addQueryParam(List<String> params, String key, String value) {
        params.add(URLEncoder.encode(key, StandardCharsets.UTF_8)
            + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
