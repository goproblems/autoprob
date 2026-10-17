package autoprob.joseki;

import autoprob.ApiClient;
import autoprob.KataBrain;
import autoprob.QueryBuilder;
import autoprob.api.JosekiAnalysisResultData;
import autoprob.api.JosekiAnalysisRequestData;
import autoprob.api.JosekiAnalysisSubmitBody;
import autoprob.api.JosekiAnalysisWorkProgress;
import autoprob.api.JosekiHumanPolicyDistributionData;
import autoprob.api.JosekiHumanPolicyTaskListResponse;
import autoprob.api.JosekiHumanPolicyTaskProgress;
import autoprob.api.JosekiNodeEntry;
import autoprob.api.JosekiNodeListResponse;
import autoprob.api.JosekiPassAnalysisUpdateData;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;
import autoprob.go.action.SizeAction;
import autoprob.go.parse.Parser;
import autoprob.katastruct.AllowMove;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;
import autoprob.katastruct.MoveInfo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.Point;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class JosekiNodeRecalculator {
    public static final int CLIENT_VERSION = 4;
    private static final int NODE_PAGE_LIMIT = 500;
    private static final int DEFAULT_API_MAX_ATTEMPTS = 20;
    private static final long DEFAULT_API_RETRY_DELAY_MS = 5000L;
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";
    private static final DecimalFormat DF = new DecimalFormat("0.00");
    private static final int ROOT_LOCAL_SIZE = 10;
    private static final List<String> DEFAULT_RANK_RANKS =
        List.of("20k", "10k", "5k", "1d", "5d", "9d");
    private static final List<String> DEFAULT_PREAZ_RANKS = List.of(
        "20k", "15k", "10k", "5k", "3k", "1k",
        "1d", "3d", "5d", "7d", "9d"
    );
    private static final List<Integer> DEFAULT_PROFESSIONAL_YEARS =
        List.of(1950, 1980, 2000, 2023);

    private final Properties props;
    private final ApiClient apiClient = new ApiClient();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<Integer, Set<String>> childMovesByParentId = new HashMap<>();
    private final Map<Path, String> analysisFileHashes = new HashMap<>();
    private int queryCounter = 0;
    private Integer requestVisits;
    private String requestModelPath;
    private static final Set<String> REQUEST_MODELS = Set.of(
        "kata1-b15c192-s1672170752-d466197061.txt.gz",
        "kata1-b18c384nbt-s9996604416-d4316597426.bin.gz",
        "kata1-b28c512nbt-s7332806912-d4357057652.bin.gz",
        "kata1-tf3-b11c768-s11003M-d5973M-7gres.bin.gz"
    );

    enum AnalysisScope {
        LOCAL("local", true, false),
        LOCAL_WITH_MOVES("local_with_moves", true, true),
        GLOBAL("global", false, false);

        private final String value;
        private final boolean restrictToNearbyMoves;
        private final boolean includeExistingMoves;

        AnalysisScope(String value, boolean restrictToNearbyMoves, boolean includeExistingMoves) {
            this.value = value;
            this.restrictToNearbyMoves = restrictToNearbyMoves;
            this.includeExistingMoves = includeExistingMoves;
        }

        private static AnalysisScope fromConfig(String value) {
            String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (normalized) {
                case "local" -> LOCAL;
                case "local_with_moves" -> LOCAL_WITH_MOVES;
                case "global" -> GLOBAL;
                default -> throw new IllegalArgumentException("Unknown joseki analysis scope: " + value);
            };
        }
    }

    public JosekiNodeRecalculator(Properties props) {
        this.props = props;
        this.props.putIfAbsent(
            "api.joseki.analysis_work_progress",
            "api/joseki-analysis-work/progress"
        );
        this.props.putIfAbsent(
            "api.joseki.analysis_request_heartbeat",
            "api/joseki-analysis-requests/{id}/handles/{handleId}/heartbeat"
        );
    }

    public void run() throws Exception {
        boolean humanPolicyOnly = humanPolicyOnly();
        boolean passOnly = passOnly();
        if (humanPolicyOnly && passOnly) {
            throw new IllegalArgumentException(
                "humanPolicyOnly=true and passOnly=true cannot be used together"
            );
        }
        boolean force = forceRecalculate();
        List<AnalysisScope> scopes = humanPolicyOnly || passOnly ? List.of() : analysisScopes();
        if (humanPolicyOnly) {
            if (!humanModelConfigured()) {
                throw new IllegalArgumentException(
                    "humanPolicyOnly=true requires kata.human_model to be configured"
                );
            }
            System.out.println("Filling only missing human policies in breadth-first order"
                + " (limit=" + NODE_PAGE_LIMIT + ")");
        } else if (passOnly) {
            System.out.println("Backfilling only missing full-board Pass analysis"
                + " (limit=" + NODE_PAGE_LIMIT + ")");
        } else {
            System.out.println(force
                ? "Processing joseki nodes breadth-first, filling missing Human Policy and recalculating scopes "
                    + formatScopes(scopes) + " (limit=" + NODE_PAGE_LIMIT + ")"
                : "Processing joseki nodes breadth-first, filling missing Human Policy, scopes "
                    + formatScopes(scopes) + ", and Pass analysis (limit=" + NODE_PAGE_LIMIT + ")");
        }

        int processedNodes = 0;
        int submittedResults = 0;
        int submittedHumanPolicies = 0;
        int failedNodes = 0;
        KataBrain brain = new KataBrain(props);
        try {
            if (humanPolicyOnly) {
                RecalculationResult humanPolicyResult = processMissingHumanPolicies(brain, new HashSet<>());
                processedNodes += humanPolicyResult.processedNodes();
                submittedHumanPolicies += humanPolicyResult.submittedHumanPolicies();
                failedNodes += humanPolicyResult.failedNodes();
            } else if (passOnly) {
                JosekiNodeListResponse targetPage = fetchAnalysisWork(
                    1,
                    0,
                    List.of(AnalysisScope.GLOBAL),
                    null,
                    null
                );
                AnalysisTarget target = analysisTarget(targetPage);
                validateConfiguredAnalysisTarget(brain, target);
                System.out.println("Joseki analysis target: model=" + target.model()
                    + " minVisits=" + target.minVisits());
                List<AnalysisScope> passScopes = List.of(AnalysisScope.GLOBAL);
                RecalculationResult missingPass = processScope(
                    brain,
                    AnalysisScope.GLOBAL,
                    passScopes,
                    false,
                    true,
                    new HashSet<>()
                );
                processedNodes += missingPass.processedNodes();
                submittedResults += missingPass.submittedResults();
                failedNodes += missingPass.failedNodes();
            } else {
                RecalculationResult result = processAnalysisWork(brain, scopes, force);
                processedNodes = result.processedNodes();
                submittedResults = result.submittedResults();
                submittedHumanPolicies = result.submittedHumanPolicies();
                failedNodes = result.failedNodes();
            }
        } finally {
            brain.stopKataBrain();
        }

        System.out.println("Joseki recalculation complete. Completed " + processedNodes
            + " nodes, submitted " + submittedResults + " analysis results or pass updates + " + submittedHumanPolicies
            + " human policies, failed " + failedNodes + " nodes.");
    }

    private RecalculationResult processAnalysisWork(
        KataBrain brain, List<AnalysisScope> scopes, boolean force
    ) throws Exception {
        long startedAt = System.currentTimeMillis();
        JosekiNodeListResponse firstPage = fetchAnalysisWork(NODE_PAGE_LIMIT, 0, scopes, null, null);
        long pageFetchDurationMs = System.currentTimeMillis() - startedAt;
        int pagesFetched = 1;
        AnalysisTarget analysisTarget = analysisTarget(firstPage);
        validateConfiguredAnalysisTarget(brain, analysisTarget);
        if (firstPage.snapshotMaxNodeId == null) {
            throw new IllegalStateException("Analysis work API did not return snapshotMaxNodeId");
        }
        int snapshotMaxNodeId = firstPage.snapshotMaxNodeId;
        int totalNodes = firstPage.totalRecords;
        System.out.println("Joseki analysis target: model=" + analysisTarget.model()
            + " minVisits=" + analysisTarget.minVisits());
        System.out.println("Analysis snapshot: " + totalNodes + " nodes (max id="
            + snapshotMaxNodeId + ")");

        int pendingWorkNodes = totalNodes;
        if (!force && totalNodes > 0) {
            JosekiAnalysisWorkProgress progress = fetchAnalysisWorkProgress(scopes, snapshotMaxNodeId);
            if (progress.totalNodes != totalNodes
                || !Integer.valueOf(snapshotMaxNodeId).equals(progress.snapshotMaxNodeId)
                || !analysisTarget.model().equals(progress.targetModel)
                || !Integer.valueOf(analysisTarget.minVisits()).equals(progress.targetMinVisits)
                || progress.pendingNodes < 0 || progress.pendingNodes > totalNodes
                || progress.completedNodes != totalNodes - progress.pendingNodes) {
                throw new IllegalStateException("Analysis work progress does not match the node snapshot");
            }
            pendingWorkNodes = progress.pendingNodes;
        }
        System.out.println("Nodes requiring analysis: " + pendingWorkNodes + "/" + totalNodes);
        if (pendingWorkNodes == 0) {
            return new RecalculationResult(totalNodes, 0, 0, 0);
        }

        int completedNodes = 0;
        int completedWorkNodes = 0;
        int visitedNodes = 0;
        int attemptedWorkNodes = 0;
        int skippedNodes = 0;
        long workDurationMs = 0L;
        long skipDurationMs = 0L;
        int submittedResults = 0;
        int submittedHumanPolicies = 0;
        int failedNodes = 0;
        int offset = 0;
        JosekiNodeListResponse page = firstPage;

        while (true) {
            if (!analysisTarget.equals(analysisTarget(page))) {
                throw new IllegalStateException("Joseki analysis target changed while scanning");
            }
            if (!Integer.valueOf(snapshotMaxNodeId).equals(page.snapshotMaxNodeId)
                || page.totalRecords != totalNodes) {
                throw new IllegalStateException("Analysis work snapshot changed while scanning");
            }
            List<JosekiNodeEntry> entries = page.entries == null ? List.of() : page.entries;
            if (entries.isEmpty()) {
                break;
            }
            int visitedOnPage = 0;
            for (JosekiNodeEntry entry : entries) {
                String path = entry.path == null ? "" : entry.path;
                boolean requiresWork = force || hasMissingAnalysisWork(entry);
                long nodeStartedAt = System.currentTimeMillis();
                boolean completed = false;
                try {
                    if (entry.missingHumanPolicyProfiles == null || entry.missingAnalysisScopes == null) {
                        throw new IllegalStateException("Analysis work API did not return component status");
                    }
                    List<AnalysisScope> neededScopes = force
                        ? scopes
                        : entry.missingAnalysisScopes.stream().map(AnalysisScope::fromConfig).toList();
                    boolean neededPass = force || entry.passAnalysisMissing;
                    boolean alreadyComplete = !hasMissingAnalysisWork(entry);
                    if (!entry.missingHumanPolicyProfiles.isEmpty() && !humanModelConfigured()) {
                        throw new IllegalStateException("Missing Human Policy requires kata.human_model");
                    }
                    boolean humanPolicyAlreadyComplete = entry.missingHumanPolicyProfiles.isEmpty();
                    List<JosekiHumanPolicyDistributionData> humanPolicies = humanPolicyAlreadyComplete
                        ? List.of()
                        : analyzeHumanPolicies(entry, brain, entry.missingHumanPolicyProfiles);
                    String humanPolicyStatus = humanPolicyAlreadyComplete
                        ? "skipped: all requested profiles are already complete"
                        : (humanPolicies.isEmpty()
                            ? "not submitted: no distribution was produced; see earlier warnings"
                            : null);
                    List<JosekiAnalysisResultData> results = neededScopes.isEmpty()
                        ? List.of()
                        : analyzeNode(entry, brain, neededScopes, neededPass);
                    if (!results.isEmpty() || !humanPolicies.isEmpty()) {
                        submitResults(results, humanPolicies, humanPolicyStatus);
                        submittedResults += results.size();
                        submittedHumanPolicies += humanPolicies.size();
                    }
                    if (neededPass && neededScopes.isEmpty()) {
                        submitPassAnalysisUpdate(analyzePassNode(entry, brain, null));
                        submittedResults++;
                    }
                    if ((!alreadyComplete || force) && !isAnalysisWorkComplete(entry, scopes, analysisTarget)) {
                        throw new IllegalStateException("Node still has missing Human Policy, scope, or Pass analysis after submission");
                    }
                    completedNodes++;
                    completed = true;
                } catch (Exception ex) {
                    failedNodes++;
                    System.out.println(RED + "Failed to complete joseki node " + entry.id
                        + " path=" + formatPath(path) + ": " + describeException(ex) + RESET);
                    if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                        ex.printStackTrace(System.out);
                    }
                } finally {
                    long nodeDurationMs = Math.max(0L, System.currentTimeMillis() - nodeStartedAt);
                    visitedNodes++;
                    visitedOnPage++;
                    if (requiresWork) {
                        attemptedWorkNodes++;
                        workDurationMs += nodeDurationMs;
                    } else {
                        skippedNodes++;
                        skipDurationMs += nodeDurationMs;
                    }
                    if (completed && requiresWork) {
                        completedWorkNodes++;
                    }
                    Long remainingMs = attemptedWorkNodes < 3 || visitedNodes == totalNodes
                        ? null : estimateAnalysisRemainingMs(
                        totalNodes, pendingWorkNodes, visitedNodes, attemptedWorkNodes, skippedNodes,
                        workDurationMs, skipDurationMs, pageFetchDurationMs, pagesFetched
                    );
                    if (requiresWork || visitedNodes % 100 == 0 || visitedOnPage == entries.size()) {
                        printProgressBar("Analysis", completedWorkNodes, pendingWorkNodes,
                            entry, startedAt, remainingMs,
                            " scanned=" + visitedNodes + "/" + totalNodes);
                    }
                }
            }
            offset += entries.size();
            if (offset < totalNodes) {
                long fetchStartedAt = System.currentTimeMillis();
                page = fetchAnalysisWork(NODE_PAGE_LIMIT, offset, scopes, null, snapshotMaxNodeId);
                pageFetchDurationMs += System.currentTimeMillis() - fetchStartedAt;
                pagesFetched++;
            } else {
                break;
            }
        }
        return new RecalculationResult(completedNodes, submittedResults, submittedHumanPolicies, failedNodes);
    }

    private boolean hasMissingAnalysisWork(JosekiNodeEntry entry) {
        if (entry.missingHumanPolicyProfiles == null || entry.missingAnalysisScopes == null) {
            throw new IllegalStateException("Analysis work API did not return component status");
        }
        return !entry.missingHumanPolicyProfiles.isEmpty()
            || !entry.missingAnalysisScopes.isEmpty() || entry.passAnalysisMissing;
    }

    static long estimateAnalysisRemainingMs(
        int totalNodes, int pendingWorkNodes, int visitedNodes,
        int attemptedWorkNodes, int skippedNodes,
        long workDurationMs, long skipDurationMs, long pageFetchDurationMs, int pagesFetched
    ) {
        int remainingWork = Math.max(0, pendingWorkNodes - attemptedWorkNodes);
        int remainingSkipped = Math.max(0, totalNodes - pendingWorkNodes - skippedNodes);
        int remainingNodes = Math.max(0, totalNodes - visitedNodes);
        double estimate = attemptedWorkNodes == 0 ? 0.0
            : remainingWork * (workDurationMs / (double) attemptedWorkNodes);
        if (skippedNodes > 0) {
            estimate += remainingSkipped * (skipDurationMs / (double) skippedNodes);
        }
        if (pagesFetched > 0) {
            int remainingPages = (remainingNodes + NODE_PAGE_LIMIT - 1) / NODE_PAGE_LIMIT;
            estimate += remainingPages * (pageFetchDurationMs / (double) pagesFetched);
        }
        return Math.round(Math.min(Long.MAX_VALUE, estimate));
    }

    private boolean isAnalysisWorkComplete(
        JosekiNodeEntry entry,
        List<AnalysisScope> scopes,
        AnalysisTarget expectedTarget
    ) throws Exception {
        JosekiNodeListResponse page = fetchAnalysisWork(1, 0, scopes, entry.path == null ? "" : entry.path, null);
        if (!expectedTarget.equals(analysisTarget(page))) {
            throw new IllegalStateException("Joseki analysis target changed while verifying a node");
        }
        if (page.entries == null || page.entries.size() != 1) {
            return false;
        }
        JosekiNodeEntry current = page.entries.get(0);
        return current.missingHumanPolicyProfiles != null && current.missingHumanPolicyProfiles.isEmpty()
            && current.missingAnalysisScopes != null && current.missingAnalysisScopes.isEmpty()
            && !current.passAnalysisMissing;
    }

    public void runAnalysisRequests() throws Exception {
        List<AnalysisScope> scopes = analysisScopes();
        long pollIntervalMs = Math.max(250L, Long.parseLong(
            props.getProperty("joseki.request_poll_interval_ms", "2000")
        ));
        System.out.println("Waiting for josekipedia analysis requests"
            + " (scopes=" + formatScopes(scopes)
            + ", poll interval=" + pollIntervalMs + "ms)");

        KataBrain brain = null;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                JosekiAnalysisRequestData request;
                try {
                    request = fetchNextAnalysisRequest();
                } catch (SecurityException ex) {
                    throw ex;
                } catch (Exception ex) {
                    System.out.println(RED + "Failed to fetch next joseki analysis request: "
                        + describeException(ex) + RESET);
                    sleepForRequests(pollIntervalMs);
                    continue;
                }

                if (request == null) {
                    sleepForRequests(pollIntervalMs);
                    continue;
                }

                long startedAt = System.currentTimeMillis();
                String path = request.path == null ? "" : request.path;
                AtomicBoolean leaseLost = new AtomicBoolean(false);
                AtomicLong lastHeartbeat = new AtomicLong(startedAt);
                ScheduledExecutorService heartbeatExecutor = startAnalysisHeartbeat(
                    request, leaseLost, lastHeartbeat
                );
                try {
                    if (request.visits != null && (request.visits < 100 || request.visits > 20000)) {
                        throw new IllegalArgumentException("Requested visits must be between 100 and 20000");
                    }
                    requestVisits = request.visits;
                    requestModelPath = resolveRequestModelPath(request.model);
                    String selectedModelPath = requestModelPath == null
                        ? props.getProperty("kata.model") : requestModelPath;
                    if (brain == null || !Path.of(selectedModelPath).toAbsolutePath().normalize()
                        .equals(Path.of(brain.modelPath).toAbsolutePath().normalize())) {
                        if (brain != null) {
                            brain.stopKataBrain();
                            brain = null;
                        }
                        brain = new KataBrain(props, selectedModelPath);
                    }
                    System.out.println("Processing joseki analysis request " + request.id
                        + " source=" + request.source
                        + " path=" + formatPath(path)
                        + " visits=" + (requestVisits == null ? props.getProperty("joseki.visits", "1000") : requestVisits)
                        + " model=" + katagoWeightsFile());
                    JosekiNodeEntry entry = resolveNodeForAnalysis(path);
                    List<JosekiAnalysisResultData> results = analyzeNode(entry, brain, scopes);
                    boolean customAnalysisRequest = request.visits != null || request.model != null;
                    List<JosekiHumanPolicyDistributionData> humanPolicyDistributions = customAnalysisRequest
                        ? List.of()
                        : analyzeHumanPolicies(entry, brain, humanSLProfiles());
                    String humanPolicyStatus = customAnalysisRequest
                        ? customAnalysisHumanPolicyStatus(request)
                        : (humanPolicyDistributions.isEmpty()
                            ? "not submitted: no distribution was produced; see earlier warnings"
                            : null);
                    if (leaseLost.get() || (heartbeatExecutor != null
                        && System.currentTimeMillis() - lastHeartbeat.get() >= 15000L)) {
                        throw new IllegalStateException("Analysis request handle is no longer current");
                    }
                    long durationMs = Math.max(1L, System.currentTimeMillis() - startedAt);
                    submitResults(
                        results,
                        humanPolicyDistributions,
                        request.id,
                        request.source == null ? "recalculate" : request.source,
                        durationMs,
                        humanPolicyStatus
                    );
                    System.out.println("Completed joseki analysis request " + request.id
                        + " with " + humanPolicyDistributions.size()
                        + " Human Policy distributions in " + durationMs + "ms");
                } catch (Exception ex) {
                    String message = describeException(ex);
                    System.out.println(RED + "Failed joseki analysis request " + request.id
                        + " path=" + formatPath(path) + ": " + message + RESET);
                    if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                        ex.printStackTrace(System.out);
                    }
                    if (!leaseLost.get() && (heartbeatExecutor == null
                        || System.currentTimeMillis() - lastHeartbeat.get() < 15000L)) {
                        reportAnalysisRequestError(request.id, message);
                    }
                } finally {
                    if (heartbeatExecutor != null) {
                        heartbeatExecutor.shutdownNow();
                    }
                    requestVisits = null;
                    requestModelPath = null;
                }
            }
        } finally {
            if (brain != null) {
                brain.stopKataBrain();
            }
        }
    }

    private String customAnalysisHumanPolicyStatus(JosekiAnalysisRequestData request) {
        if (request.visits != null && request.model != null) {
            return "skipped: request overrides visits and model";
        }
        if (request.visits != null) {
            return "skipped: request overrides visits";
        }
        return "skipped: request overrides model";
    }

    private record ProcessNodesResult(int submittedResults, int submittedHumanPolicies, int failedNodes) {}

    private record ScopedQueryResult(KataAnalysisResult analysis, double policyMass, String configHash) {}

    private record AnalysisTarget(String model, int minVisits) {}

    private record RecalculationResult(
        int processedNodes,
        int submittedResults,
        int submittedHumanPolicies,
        int failedNodes
    ) {}

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private RecalculationResult processScope(
        KataBrain brain,
        AnalysisScope candidateScope,
        List<AnalysisScope> analysisScopes,
        boolean force,
        boolean passAnalysisMissing,
        Set<Integer> processedNodeIds
    ) throws Exception {
        System.out.println("Starting joseki recalculation candidate scope=" + candidateScope.value
            + ", analysis scopes=" + formatScopes(analysisScopes)
            + (passAnalysisMissing ? ", missing full-board pass analysis only" : ""));

        int submittedResults = 0;
        int submittedHumanPolicies = 0;
        int failedNodes = 0;
        int queryOffset = 0;
        int processedNodesBefore = processedNodeIds.size();

        while (true) {
            JosekiNodeListResponse page = fetchNodes(
                NODE_PAGE_LIMIT, queryOffset, candidateScope, passAnalysisMissing
            );
            List<JosekiNodeEntry> entries = page.entries == null ? List.of() : page.entries;
            if (entries.isEmpty()) {
                if (queryOffset == 0) {
                    System.out.println("No more joseki nodes to recalculate for candidate scope="
                        + candidateScope.value + ".");
                } else {
                    System.out.println("No more joseki nodes after skipping nodes already attempted in this run for candidate scope="
                        + candidateScope.value + ".");
                }
                break;
            }

            int before = processedNodeIds.size();
            int totalNodesEstimate = force
                ? page.totalRecords
                : Math.max(processedNodeIds.size() + page.totalRecords, processedNodeIds.size());
            ProcessNodesResult result = processNodes(
                entries,
                brain,
                candidateScope,
                analysisScopes,
                passAnalysisMissing,
                processedNodeIds,
                totalNodesEstimate
            );
            submittedResults += result.submittedResults();
            submittedHumanPolicies += result.submittedHumanPolicies();
            failedNodes += result.failedNodes();

            System.out.println("Joseki recalculation candidate scope=" + candidateScope.value
                + ": processed " + processedNodeIds.size()
                + " nodes, submitted " + submittedResults
                + (passAnalysisMissing ? " pass updates + " : " analysis results + ")
                + submittedHumanPolicies + " human policies, failed " + failedNodes
                + " nodes. Remaining reported by API after current offset: "
                + Math.max(0, page.totalRecords - queryOffset - entries.size()));

            if (force || processedNodeIds.size() == before) {
                queryOffset += entries.size();
                if (processedNodeIds.size() == before) {
                    System.out.println("No new joseki nodes processed from latest page for candidate scope="
                        + candidateScope.value
                        + "; advancing offset to " + queryOffset + " to skip nodes already attempted in this run.");
                }
            } else {
                queryOffset = 0;
            }
        }

        return new RecalculationResult(
            processedNodeIds.size() - processedNodesBefore,
            submittedResults,
            submittedHumanPolicies,
            failedNodes
        );
    }

    private RecalculationResult processMissingHumanPolicies(
        KataBrain brain,
        Set<Integer> processedNodeIds
    ) throws Exception {
        System.out.println("Filling missing human policy profiles in breadth-first order...");

        int submittedHumanPolicies = 0;
        int failedNodes = 0;
        int queryOffset = 0;
        int processedNodesBefore = processedNodeIds.size();

        JosekiHumanPolicyTaskProgress progress = fetchHumanPolicyTaskProgress();
        int totalTasksEstimate = remainingHumanPolicyTasks(progress);
        long startedAt = System.currentTimeMillis();
        System.out.println("Human Policy tasks this run: 0/" + totalTasksEstimate
            + " missing at start"
            + " (completed=" + progress.completedNodes
            + ", ready=" + progress.readyNodes
            + ", blocked=" + progress.blockedNodes + ").");

        while (true) {
            JosekiHumanPolicyTaskListResponse page = fetchMissingHumanPolicyNodes(NODE_PAGE_LIMIT, queryOffset);
            List<JosekiNodeEntry> entries = page.entries == null ? List.of() : page.entries;
            if (entries.isEmpty()) {
                System.out.println("No more joseki nodes missing human policy.");
                break;
            }

            int processedThisRun = processedNodeIds.size() - processedNodesBefore;
            // Older Josekipedia servers can report pending=0 while still
            // returning a page of runnable tasks. Keep the progress display
            // useful until the server-side progress fix is deployed.
            totalTasksEstimate = Math.max(
                totalTasksEstimate,
                processedThisRun + entries.size()
            );
            sortEntriesByBreadth(entries);
            int before = processedNodeIds.size();
            for (JosekiNodeEntry entry : entries) {
                if (!processedNodeIds.add(entry.id)) {
                    continue;
                }

                String path = entry.path == null ? "" : entry.path;
                try {
                    printProgressBar(
                        "Human Policy",
                        processedNodeIds.size() - processedNodesBefore,
                        totalTasksEstimate,
                        entry,
                        startedAt
                    );
                    if (entry.missingHumanPolicyProfiles == null) {
                        throw new IllegalStateException(
                            "Josekipedia API did not return missingHumanPolicyProfiles"
                        );
                    }
                    List<JosekiHumanPolicyDistributionData> distributions = analyzeHumanPolicies(
                        entry,
                        brain,
                        entry.missingHumanPolicyProfiles
                    );
                    if (!distributions.isEmpty()) {
                        submitResults(List.of(), distributions);
                    }
                    submittedHumanPolicies += distributions.size();
                } catch (Exception ex) {
                    failedNodes++;
                    System.out.println(RED + "Failed to fill human policy for joseki node " + entry.id
                        + " path=" + formatPath(path)
                        + ": " + describeException(ex)
                        + RESET);
                    if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                        ex.printStackTrace(System.out);
                    }
                }
            }

            progress = fetchHumanPolicyTaskProgress();
            processedThisRun = processedNodeIds.size() - processedNodesBefore;
            totalTasksEstimate = Math.max(
                processedThisRun,
                processedThisRun + remainingHumanPolicyTasks(progress)
            );
            System.out.println("Missing human policy pass: processed " + processedNodeIds.size()
                + " nodes, submitted " + submittedHumanPolicies + " human policies, failed " + failedNodes
                + " nodes. Refreshed progress=" + processedThisRun + "/" + totalTasksEstimate
                + " (completed=" + progress.completedNodes
                + ", pending=" + progress.pendingNodes
                + ", ready=" + progress.readyNodes
                + ", blocked=" + progress.blockedNodes + ").");

            if (processedNodeIds.size() != before) {
                queryOffset = 0;
            } else {
                queryOffset += entries.size();
            }
        }

        return new RecalculationResult(
            processedNodeIds.size() - processedNodesBefore,
            0,
            submittedHumanPolicies,
            failedNodes
        );
    }

    private int remainingHumanPolicyTasks(JosekiHumanPolicyTaskProgress progress) {
        return Math.max(0, Math.max(progress.pendingNodes, progress.readyNodes));
    }

    private ProcessNodesResult processNodes(List<JosekiNodeEntry> entries, KataBrain brain,
                                            AnalysisScope candidateScope, List<AnalysisScope> analysisScopes,
                                            boolean passAnalysisMissing,
                                            Set<Integer> processedNodeIds,
                                            int totalNodesEstimate) {
        sortEntries(entries);

        int submittedResults = 0;
        int submittedHumanPolicies = 0;
        int failedNodes = 0;
        for (JosekiNodeEntry entry : entries) {
            if (!processedNodeIds.add(entry.id)) {
                continue;
            }

            String path = entry.path == null ? "" : entry.path;
            try {
                printProgressBar(
                    passAnalysisMissing ? "Pass backfill" : "Analysis",
                    processedNodeIds.size(), totalNodesEstimate, entry
                );
                System.out.println("Recalculating joseki node " + entry.id
                    + " candidateScope=" + candidateScope.value
                    + " analysisScopes=" + formatScopes(analysisScopes)
                    + " path=" + formatPath(path)
                    + " version=" + entry.analysisClientVersion);

                if (passAnalysisMissing) {
                    submitPassAnalysisUpdate(analyzePassNode(entry, brain, null));
                    submittedResults++;
                } else {
                    List<JosekiAnalysisResultData> results = analyzeNode(entry, brain, analysisScopes);
                    if (!results.isEmpty()) {
                        submitResults(
                            results,
                            List.of(),
                            "skipped: this is an analysis-only recalculation"
                        );
                    }
                    submittedResults += results.size();
                }
            } catch (Exception ex) {
                failedNodes++;
                System.out.println(RED + "Failed to recalculate joseki node " + entry.id
                    + " candidateScope=" + candidateScope.value
                    + " path=" + formatPath(path)
                    + " version=" + entry.analysisClientVersion
                    + ": " + describeException(ex)
                    + RESET);
                if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                    ex.printStackTrace(System.out);
                }
            }
        }
        return new ProcessNodesResult(submittedResults, submittedHumanPolicies, failedNodes);
    }

    private void sortEntries(List<JosekiNodeEntry> entries) {
        if ("breadth".equals(props.getProperty("sort"))) {
            sortEntriesByBreadth(entries);
            return;
        }

        entries.sort(Comparator
            .comparing((JosekiNodeEntry entry) -> entry.path == null || entry.path.isEmpty() ? 0 : 1)
            .thenComparing(entry -> entry.path == null ? "" : entry.path));
    }

    private void sortEntriesByBreadth(List<JosekiNodeEntry> entries) {
        entries.sort(Comparator
            .comparingInt((JosekiNodeEntry entry) -> pathDepth(entry.path))
            .thenComparing(entry -> entry.path == null ? "" : entry.path)
            .thenComparingInt(entry -> entry.id));
    }

    private int pathDepth(String path) {
        if (path == null || path.isBlank()) {
            return 0;
        }

        int depth = 1;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == ',') {
                depth++;
            }
        }
        return depth;
    }

    private List<JosekiAnalysisResultData> analyzeNode(
        JosekiNodeEntry entry,
        KataBrain brain,
        List<AnalysisScope> scopes
    ) throws Exception {
        return analyzeNode(entry, brain, scopes, true);
    }

    private List<JosekiAnalysisResultData> analyzeNode(
        JosekiNodeEntry entry,
        KataBrain brain,
        List<AnalysisScope> scopes,
        boolean includePass
    ) throws Exception {
        List<JosekiAnalysisResultData> results = new ArrayList<>();
        for (AnalysisScope scope : scopes) {
            results.add(analyzeNode(entry, brain, scope));
        }
        if (includePass && !results.isEmpty()) {
            JosekiAnalysisResultData carrier = results.stream()
                .filter(result -> AnalysisScope.GLOBAL.value.equals(result.scope))
                .findFirst()
                .orElse(results.get(0));
            Double currentGlobalScore = AnalysisScope.GLOBAL.value.equals(carrier.scope)
                ? carrier.score
                : null;
            JosekiPassAnalysisUpdateData pass = analyzePassNode(entry, brain, currentGlobalScore);
            carrier.passScore = pass.passScore;
            carrier.passLoss = pass.passLoss;
            carrier.passVisits = pass.passVisits;
            carrier.passAnalysis = pass.passAnalysis;
            carrier.passAnalysisConfigHash = pass.analysisConfigHash;
        }
        return results;
    }

    private JosekiPassAnalysisUpdateData analyzePassNode(
        JosekiNodeEntry entry, KataBrain brain, Double currentGlobalScore
    ) throws Exception {
        String path = entry.path == null ? "" : entry.path;
        Node node = buildNode(path);
        double baselineScore = currentGlobalScore == null
            ? queryNode(brain, node, "pass-baseline", AnalysisScope.GLOBAL, Set.of())
                .analysis().blackScore()
            : currentGlobalScore;
        Node passNode = node.addBasicMove(19, 19);
        ScopedQueryResult passQueryResult;
        try {
            passQueryResult = queryNode(brain, passNode, "pass", AnalysisScope.GLOBAL, Set.of());
        } finally {
            node.removeChildNode(passNode);
        }
        KataAnalysisResult passResult = passQueryResult.analysis();

        JosekiPassAnalysisUpdateData update = new JosekiPassAnalysisUpdateData();
        update.path = path;
        update.passScore = passResult.blackScore();
        update.passLoss = -moveScoreDelta(passNode, baselineScore, update.passScore);
        if (passResult.rootInfo == null || passResult.rootInfo.visits == null) {
            throw new IllegalStateException("KataGo pass analysis has no root visit count for " + formatPath(path));
        }
        update.passVisits = passResult.rootInfo.visits;
        update.passAnalysis = gson.toJson(passResult);
        update.katagoPlayouts = configuredVisits();
        update.katagoWeightsFile = katagoWeightsFile();
        update.analysisConfigHash = passQueryResult.configHash();
        return update;
    }

    private JosekiAnalysisResultData analyzeNode(
        JosekiNodeEntry entry,
        KataBrain brain,
        AnalysisScope scope
    ) throws Exception {
        String path = entry.path == null ? "" : entry.path;
        Node node = buildNode(path);
        Set<String> currentChildMoves = scope.includeExistingMoves && entry.id > 0
            ? fetchChildMoves(entry.id)
            : Set.of();
        Set<String> parentChildMoves = new LinkedHashSet<>();
        String nodeMove = lastMove(path);

        if (scope.includeExistingMoves && node.mom != null) {
            parentChildMoves = entry.parentId == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(fetchChildMoves(entry.parentId));
            if (nodeMove != null) {
                parentChildMoves.add(nodeMove);
            }
        }

        KataAnalysisResult parentResult = null;
        MoveInfo moveInfo = null;
        Double parentScore = null;

        if (node.mom != null) {
            ScopedQueryResult parentQueryResult = queryNode(
                brain,
                node.mom,
                "parent",
                scope,
                parentChildMoves
            );
            parentResult = parentQueryResult.analysis();
            parentScore = parentResult.blackScore();
            moveInfo = findMoveInfo(parentResult, node);
        }

        ScopedQueryResult currentQueryResult = queryNode(
            brain,
            node,
            "current",
            scope,
            currentChildMoves
        );
        KataAnalysisResult currentResult = currentQueryResult.analysis();
        double score = currentResult.blackScore();
        Double moverScoreDelta = parentScore == null ? null : moveScoreDelta(node, parentScore, score);

        JosekiAnalysisResultData result = new JosekiAnalysisResultData();
        result.path = path;
        result.scope = scope.value;
        result.score = score;
        result.loss = moverScoreDelta == null ? 0.0 : -moverScoreDelta;
        result.katagoPlayouts = configuredVisits();
        result.katagoWeightsFile = katagoWeightsFile();
        result.prior = moveInfo == null ? null : moveInfo.prior;
        result.policyMass = currentQueryResult.policyMass();
        result.visits = moveInfo == null ? null : moveInfo.visits;
        result.moveOrder = moveInfo == null ? null : moveInfo.order;
        result.extraInfo = buildExtraInfo(scope, parentScore, score, moverScoreDelta);
        result.analysis = gson.toJson(currentResult);
        result.analysisConfigHash = currentQueryResult.configHash();
        System.out.println("Joseki node " + formatPath(path)
            + " scope=" + scope.value
            + " score=" + DF.format(result.score)
            + " loss=" + DF.format(result.loss)
            + (result.prior == null ? "" : " prior=" + DF.format(result.prior * 1000.0))
            + (result.visits == null ? "" : " visits=" + result.visits)
            + (result.moveOrder == null ? "" : " order=" + result.moveOrder));
        return result;
    }

    private ScopedQueryResult queryNode(
        KataBrain brain,
        Node node,
        String role,
        AnalysisScope scope,
        Set<String> childMoves
    ) throws Exception {
        KataQuery query = buildScopedQuery(node, scope, childMoves);
        query.id = "joseki:" + (++queryCounter) + ":" + role + ":" + scope.value;

        Set<String> allowedMoves = query.allowMoves == null
            ? null : new LinkedHashSet<>(query.allowMoves.get(0).moves);

        String configHash = analysisConfigHash(brain.modelPath, query, role, scope.value);

        brain.doQuery(query);
        KataAnalysisResult result = brain.getResult(query.id, 0);
        if (result == null) {
            throw new RuntimeException("No KataGo result for query " + query.id);
        }
        if (result.isError()) {
            throw new RuntimeException("KataGo error for query " + query.id + ": " + result.error);
        }
        if (result.rootInfo == null) {
            throw new RuntimeException("KataGo result has no rootInfo for query " + query.id);
        }
        double policyMass = scope == AnalysisScope.GLOBAL
            ? 1.0
            : calculatePolicyMass(result.policy, allowedMoves);
        return new ScopedQueryResult(result, policyMass, configHash);
    }

    KataQuery buildScopedQuery(Node node, AnalysisScope scope, Set<String> childMoves) {
        KataQuery query = new QueryBuilder().buildQuery(node);
        query.includePolicy = true;
        query.analyzeTurns.clear();
        query.analyzeTurns.add(0);
        query.maxVisits = configuredVisits();
        Set<String> scopedChildMoves = scope.includeExistingMoves ? childMoves : Set.of();

        int maxMoveDistance = Integer.parseInt(props.getProperty("joseki.max_move_distance", "4"));
        if (scope.restrictToNearbyMoves && maxMoveDistance >= 0) {
            restrictToMoves(node, query, nearbyMoves(node, maxMoveDistance, scopedChildMoves));
        }
        if (scope.restrictToNearbyMoves) {
            Set<String> focusMoves = new LinkedHashSet<>();
            focusMoves.add("pass");
            addChildMoves(focusMoves, node, scopedChildMoves);
            query.focusMoves = new ArrayList<>(focusMoves);
            Collections.sort(query.focusMoves);
        }
        return query;
    }

    String analysisConfigHash(String modelPath, KataQuery query, String role, String scope)
        throws Exception {
        JsonObject queryConfig = gson.toJsonTree(query).getAsJsonObject();
        queryConfig.remove("id");
        JsonElement allowMoves = queryConfig.get("allowMoves");
        if (allowMoves != null && allowMoves.isJsonArray()) {
            for (JsonElement allowMove : allowMoves.getAsJsonArray()) {
                JsonObject restriction = allowMove.getAsJsonObject();
                JsonElement moves = restriction.get("moves");
                if (moves == null || !moves.isJsonArray()) {
                    continue;
                }
                List<String> sortedMoves = new ArrayList<>();
                for (JsonElement move : moves.getAsJsonArray()) {
                    sortedMoves.add(move.getAsString());
                }
                Collections.sort(sortedMoves);
                JsonArray normalizedMoves = new JsonArray();
                for (String move : sortedMoves) {
                    normalizedMoves.add(move);
                }
                restriction.add("moves", normalizedMoves);
            }
        }

        String config = "joseki-katago-query-v1\n"
            + role + "\n"
            + scope + "\n"
            + hashAnalysisFile(resolveKatagoExecutable()) + "\n"
            + hashAnalysisFile(Path.of(modelPath)) + "\n"
            + hashAnalysisFile(Path.of(props.getProperty("kata.config"))) + "\n"
            + gson.toJson(queryConfig);
        return sha256Hex(config.getBytes(StandardCharsets.UTF_8));
    }

    private Path resolveKatagoExecutable() {
        String executable = props.getProperty("katago").trim();
        Path configured = Path.of(executable);
        if (Files.isRegularFile(configured)) {
            return configured;
        }
        String systemPath = System.getenv("PATH");
        if (systemPath != null) {
            for (String directory : systemPath.split(File.pathSeparator)) {
                Path candidate = Path.of(directory, executable);
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("Cannot fingerprint KataGo executable: " + executable);
    }

    private String hashAnalysisFile(Path path) throws Exception {
        Path normalizedPath = path.toAbsolutePath().normalize();
        String cached = analysisFileHashes.get(normalizedPath);
        if (cached != null) {
            return cached;
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(normalizedPath)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        String hash = java.util.HexFormat.of().formatHex(digest.digest());
        analysisFileHashes.put(normalizedPath, hash);
        return hash;
    }

    private String sha256Hex(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private List<JosekiHumanPolicyDistributionData> analyzeHumanPolicies(
        JosekiNodeEntry entry,
        KataBrain brain,
        List<String> profiles
    ) {
        List<JosekiHumanPolicyDistributionData> distributions = new ArrayList<>();
        if (!humanModelConfigured()) {
            System.out.println(YELLOW + "Skipping human policy fill: no kata.human_model configured" + RESET);
            return distributions;
        }

        String path = entry.path == null ? "" : entry.path;
        Node node;
        try {
            node = buildNode(path);
        } catch (Exception ex) {
            System.out.println(YELLOW + "Skipping human policy fill for joseki node " + entry.id
                + " path=" + formatPath(path)
                + ": " + describeException(ex)
                + RESET);
            return distributions;
        }

        Set<String> localMoves = null;
        int maxMoveDistance = Integer.parseInt(props.getProperty("joseki.max_move_distance", "4"));
        if (maxMoveDistance >= 0) {
            try {
                localMoves = nearbyMoves(node, maxMoveDistance, Set.of());
            } catch (Exception ex) {
                System.out.println(YELLOW + "Failed to build Human Policy normalization regions for joseki node "
                    + entry.id + " path=" + formatPath(path) + ": " + describeException(ex) + RESET);
                return distributions;
            }
        }
        for (String profile : profiles) {
            try {
                KataAnalysisResult result = queryHumanPolicy(brain, node, profile);
                if (result.humanPolicy == null || result.humanPolicy.size() != 19 * 19 + 1) {
                    throw new IllegalStateException(
                        "KataGo Human Policy distribution must contain 362 values"
                    );
                }

                JosekiHumanPolicyDistributionData distribution = new JosekiHumanPolicyDistributionData();
                distribution.path = path;
                distribution.profile = profile;
                distribution.distribution = new ArrayList<>(result.humanPolicy);
                distribution.localPolicyMass = calculatePolicyMass(result.humanPolicy, localMoves);
                distribution.maxMoveDistance = maxMoveDistance;
                distributions.add(distribution);
            } catch (Exception ex) {
                System.out.println(YELLOW + "Failed to query human policy profile=" + profile
                    + " for joseki node " + entry.id
                    + " path=" + formatPath(path)
                    + ": " + describeException(ex)
                    + RESET);
            }
        }

        if (!distributions.isEmpty()) {
            System.out.println("Joseki parent position " + formatPath(path)
                + " recorded " + distributions.size() + " Human Policy distributions ("
                + profiles.size() + " profiles queried)");
        }
        return distributions;
    }

    private KataAnalysisResult queryHumanPolicy(KataBrain brain, Node parentNode, String profile) throws Exception {
        QueryBuilder queryBuilder = new QueryBuilder();
        KataQuery query = queryBuilder.buildQueryWithHistory(parentNode);
        query.id = "joseki-human:" + (++queryCounter) + ":" + profile;
        query.includePolicy = true;
        query.maxVisits = Integer.parseInt(props.getProperty("joseki.human_visits", "1"));

        KataQuery.OverrideSettings settings = new KataQuery.OverrideSettings();
        settings.humanSLProfile = profile;
        settings.ignorePreRootHistory = false;
        int symmetries = Integer.parseInt(props.getProperty("joseki.human_sl_symmetries", "2"));
        if (symmetries > 1) {
            settings.rootNumSymmetriesToSample = symmetries;
        }
        query.setOverrideSettings(settings);

        brain.doQuery(query);
        KataAnalysisResult result = brain.getResult(query.id, query.analyzeTurns.get(0));
        if (result == null) {
            throw new RuntimeException("No KataGo result for human policy query " + query.id);
        }
        if (result.isError()) {
            throw new RuntimeException("KataGo error for human policy query " + query.id + ": " + result.error);
        }
        if (result.rootInfo == null) {
            throw new RuntimeException("KataGo human policy result has no rootInfo for query " + query.id);
        }
        return result;
    }

    private List<String> humanSLProfiles() {
        String configured = props.getProperty("joseki.human_sl_profiles");
        if (configured != null && !configured.isBlank()) {
            List<String> profiles = new ArrayList<>();
            for (String raw : configured.split(",")) {
                String profile = raw.trim();
                if (!profile.isEmpty()) {
                    profiles.add(profile);
                }
            }
            if (!profiles.isEmpty()) {
                return profiles;
            }
        }
        return allHumanSLProfiles();
    }

    private static List<String> allHumanSLProfiles() {
        List<String> profiles = new ArrayList<>();
        for (String rank : DEFAULT_RANK_RANKS) {
            profiles.add("rank_" + rank);
        }
        for (String rank : DEFAULT_PREAZ_RANKS) {
            profiles.add("preaz_" + rank);
        }
        for (int year : DEFAULT_PROFESSIONAL_YEARS) {
            profiles.add("proyear_" + year);
        }
        return profiles;
    }

    private boolean humanModelConfigured() {
        String model = props.getProperty("kata.human_model");
        return model != null && !model.isBlank();
    }

    private Set<String> nearbyMoves(Node node, int distance, Set<String> childMoves) {
        boolean[][] allowSpots = new boolean[19][19];
        boolean hasAnchorStone = false;
        for (int x = 0; x < 19; x++) {
            for (int y = 0; y < 19; y++) {
                if (node.board.board[x][y].stone == Intersection.EMPTY) {
                    continue;
                }
                hasAnchorStone = true;
                for (int dx = -distance; dx <= distance; dx++) {
                    for (int dy = -distance; dy <= distance; dy++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx >= 0 && nx < 19 && ny >= 0 && ny < 19) {
                            allowSpots[nx][ny] = true;
                        }
                    }
                }
            }
        }

        Set<String> moves = new LinkedHashSet<>();
        if (hasAnchorStone) {
            for (int x = 0; x < 19; x++) {
                for (int y = 0; y < 19; y++) {
                    if (allowSpots[x][y] && node.board.board[x][y].stone == Intersection.EMPTY) {
                        moves.add(Intersection.toGTPloc(x, y, 19));
                    }
                }
            }
        } else {
            addUpperRightRootMoves(moves, node);
        }
        addChildMoves(moves, node, childMoves);
        moves.add("pass");

        return moves;
    }

    private void restrictToMoves(Node node, KataQuery query, Set<String> moves) {
        if (moves == null || moves.isEmpty()) {
            return;
        }

        AllowMove allowMove = new AllowMove();
        allowMove.player = Intersection.color2katagoname(node.getToMove());
        allowMove.untilDepth = 1;
        allowMove.moves = new ArrayList<>(moves);
        query.allowMoves = new ArrayList<>();
        query.allowMoves.add(allowMove);
    }

    private double calculatePolicyMass(List<Double> policy, Set<String> moves) {
        if (policy == null || policy.isEmpty()) {
            throw new IllegalStateException("KataGo result did not return policy values");
        }
        // An empty allowMoves set is not sent to KataGo, so the effective
        // query is unrestricted and its normalization mass is the full board.
        if (moves == null || moves.isEmpty()) {
            return 1.0;
        }

        double mass = 0.0;
        for (String move : moves) {
            int policyIndex;
            if ("pass".equalsIgnoreCase(move)) {
                policyIndex = 19 * 19;
            } else {
                Point point = Intersection.gtp2point(move.toUpperCase(Locale.ROOT));
                if (point.x < 0 || point.x >= 19 || point.y < 0 || point.y >= 19) {
                    continue;
                }
                policyIndex = point.x + point.y * 19;
            }

            if (policyIndex >= policy.size()) {
                continue;
            }
            Double value = policy.get(policyIndex);
            if (value != null && value > 0.0) {
                mass += value;
            }
        }

        if (mass <= 0.0) {
            throw new IllegalStateException("Allowed moves have zero policy mass");
        }
        return Math.min(1.0, mass);
    }

    private void addUpperRightRootMoves(Set<String> moves, Node node) {
        int minX = Math.max(0, node.board.boardX - ROOT_LOCAL_SIZE);
        int maxY = Math.min(ROOT_LOCAL_SIZE, node.board.boardY);
        for (int x = minX; x < node.board.boardX; x++) {
            for (int y = 0; y < maxY; y++) {
                if (node.board.board[x][y].stone == Intersection.EMPTY) {
                    moves.add(Intersection.toGTPloc(x, y, node.board.boardY));
                }
            }
        }
    }

    private void addChildMoves(Set<String> moves, Node node, Set<String> childMoves) {
        if (childMoves == null || childMoves.isEmpty()) {
            return;
        }

        for (String childMove : childMoves) {
            if (childMove == null || childMove.isBlank()) {
                continue;
            }
            if ("pass".equalsIgnoreCase(childMove)) {
                moves.add("pass");
                continue;
            }
            try {
                Point point = Intersection.gtp2point(childMove.toUpperCase(Locale.ROOT));
                if (point.x >= 0 && point.x < 19 && point.y >= 0 && point.y < 19
                    && node.board.board[point.x][point.y].stone == Intersection.EMPTY) {
                    moves.add(Intersection.toGTPloc(point.x, point.y, 19));
                }
            } catch (RuntimeException ex) {
                if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                    System.out.println("Skipping invalid joseki child move for allowMoves: " + childMove);
                }
            }
        }
    }

    private Node buildNode(String path) throws Exception {
        Node node = loadBasePosition();
        if (path == null || path.isBlank()) {
            return node;
        }

        for (String rawMove : path.split(",")) {
            String move = rawMove.trim();
            if (move.isEmpty()) {
                continue;
            }
            Point point = Intersection.gtp2point(move.toUpperCase(Locale.ROOT));
            node = node.addBasicMove(point.x, point.y);
        }
        return node;
    }

    private Node loadBasePosition() throws Exception {
        String sgfPath = props.getProperty("joseki.base_sgf");
        Node node;
        if (sgfPath == null || sgfPath.isBlank()) {
            node = new Node(null);
            node.addAct(new SizeAction(19));
        } else {
            String sgf = Files.readString(Path.of(sgfPath));
            node = new Parser().parse(sgf).advance2end();
        }
        node.getRoot().setXtraTag("KM", props.getProperty("joseki.komi", "6.5"));
        return node;
    }

    private MoveInfo findMoveInfo(KataAnalysisResult parentResult, Node node) {
        if (parentResult.moveInfos == null) {
            return null;
        }

        MoveAction moveAction = node.getMoveAction();
        if (moveAction == null) {
            return null;
        }

        Point loc = moveAction.loc;
        String move = Intersection.toGTPloc(loc.x, loc.y, node.board.boardY);
        for (MoveInfo moveInfo : parentResult.moveInfos) {
            if (moveInfo.move != null && moveInfo.move.equalsIgnoreCase(move)) {
                return moveInfo;
            }
        }
        return null;
    }

    private double moveScoreDelta(Node node, double parentScore, double score) {
        double delta = score - parentScore;
        if (node.getToMove() == Intersection.BLACK) {
            delta = -delta;
        }
        return delta;
    }

    private String buildExtraInfo(AnalysisScope scope, Double parentScore, double score, Double moverScoreDelta) {
        Map<String, Object> info = new HashMap<>();
        info.put("clientVersion", CLIENT_VERSION);
        info.put("scope", scope.value);
        info.put("parentScore", parentScore);
        info.put("score", score);
        info.put("moverScoreDelta", moverScoreDelta);
        info.put("komi", props.getProperty("joseki.komi", "6.5"));
        return gson.toJson(info);
    }

    private JosekiNodeListResponse fetchNodes(
        int limit, int offset, AnalysisScope scope, boolean passAnalysisMissing
    ) throws Exception {
        String queryString = buildNodesQuery(limit, offset, scope.value, passAnalysisMissing);
        System.out.println(GREEN + "Joseki analysis tasks API URL: "
            + apiClient.buildUrl("api.joseki.analysis_tasks", null, queryString, props) + RESET);

        return withApiRetries("fetch joseki nodes page scope=" + scope.value
            + " offset=" + offset + " limit=" + limit, () -> {
                ApiClient.ApiResponse<JosekiNodeListResponse> response = apiClient.makeGetRequest(
                    "api.joseki.analysis_tasks", null, queryString, JosekiNodeListResponse.class, props);
                if (!response.isSuccess()) {
                    throw new RuntimeException("Failed to fetch joseki analysis tasks: HTTP "
                        + response.getStatusCode() + " - " + response.getErrorMessage());
                }

                JosekiNodeListResponse page = response.getData();
                if (page == null) {
                    page = new JosekiNodeListResponse();
                    page.entries = List.of();
                }
                return page;
            });
    }

    private JosekiNodeListResponse fetchAnalysisWork(
        int limit, int offset, List<AnalysisScope> scopes, String path, Integer snapshotMaxNodeId
    ) throws Exception {
        List<String> params = new ArrayList<>();
        addQueryParam(params, "limit", String.valueOf(limit));
        addQueryParam(params, "offset", String.valueOf(offset));
        addQueryParam(params, "scopes", scopes.stream().map(scope -> scope.value).collect(java.util.stream.Collectors.joining(",")));
        addQueryParam(params, "humanPolicyProfiles", String.join(",", humanSLProfiles()));
        if (snapshotMaxNodeId != null) {
            addQueryParam(params, "maxNodeId", String.valueOf(snapshotMaxNodeId));
        }
        if (path != null) {
            addQueryParam(params, "path", path);
        } else if (props.containsKey("path")) {
            addQueryParam(params, "path", props.getProperty("path", ""));
        } else {
            addOptionalQueryParam(params, "pathPrefix");
        }
        addOptionalQueryParam(params, "includeDeleted");
        String queryString = "?" + String.join("&", params);
        return withApiRetries("fetch joseki analysis work offset=" + offset, () -> {
            ApiClient.ApiResponse<JosekiNodeListResponse> response = apiClient.makeGetRequest(
                "api.joseki.analysis_work", null, queryString, JosekiNodeListResponse.class, props);
            if (!response.isSuccess() || response.getData() == null) {
                throw new RuntimeException("Failed to fetch joseki analysis work: HTTP "
                    + response.getStatusCode() + " - " + response.getErrorMessage());
            }
            return response.getData();
        });
    }

    private JosekiAnalysisWorkProgress fetchAnalysisWorkProgress(
        List<AnalysisScope> scopes, int snapshotMaxNodeId
    ) throws Exception {
        List<String> params = new ArrayList<>();
        addQueryParam(params, "scopes", scopes.stream().map(scope -> scope.value)
            .collect(java.util.stream.Collectors.joining(",")));
        addQueryParam(params, "humanPolicyProfiles", String.join(",", humanSLProfiles()));
        addQueryParam(params, "maxNodeId", String.valueOf(snapshotMaxNodeId));
        if (props.containsKey("path")) {
            addQueryParam(params, "path", props.getProperty("path", ""));
        } else {
            addOptionalQueryParam(params, "pathPrefix");
        }
        addOptionalQueryParam(params, "includeDeleted");
        long startedAt = System.currentTimeMillis();
        ApiClient.ApiResponse<JosekiAnalysisWorkProgress> response = apiClient.makeGetRequest(
            "api.joseki.analysis_work_progress", null,
            "?" + String.join("&", params), JosekiAnalysisWorkProgress.class, props
        );
        if (!response.isSuccess() || response.getData() == null) {
            throw new RuntimeException("Failed to count joseki analysis work: HTTP "
                + response.getStatusCode() + " - " + response.getErrorMessage());
        }
        System.out.println("Counted pending analysis work in "
            + formatDuration(System.currentTimeMillis() - startedAt));
        return response.getData();
    }

    private AnalysisTarget analysisTarget(JosekiNodeListResponse page) {
        if (page.targetModel == null || page.targetModel.isBlank()
            || page.targetMinVisits == null || page.targetMinVisits < 1) {
            throw new IllegalStateException("Analysis work API did not return a valid Joseki analysis target");
        }
        return new AnalysisTarget(
            page.targetModel,
            page.targetMinVisits
        );
    }

    private void validateConfiguredAnalysisTarget(KataBrain brain, AnalysisTarget target) {
        String configuredModel = Path.of(brain.modelPath).getFileName().toString();
        if (!target.model().equals(configuredModel)) {
            throw new IllegalStateException("Configured KataGo model " + configuredModel
                + " does not match server target " + target.model());
        }
        int configuredVisits = configuredVisits();
        if (configuredVisits < target.minVisits()) {
            throw new IllegalStateException("Configured joseki visits " + configuredVisits
                + " is below server target minimum " + target.minVisits());
        }
    }

    private JosekiAnalysisRequestData fetchNextAnalysisRequest() throws Exception {
        List<String> params = new ArrayList<>();
        addQueryParam(params, "clientVersion", String.valueOf(CLIENT_VERSION));
        addQueryParam(params, "requestOptionsVersion", "1");
        String clientId = props.getProperty("clientId", "").trim();
        if (!clientId.isEmpty()) {
            addQueryParam(params, "clientId", clientId);
        }
        String queryString = "?" + String.join("&", params);
        ApiClient.ApiResponse<JosekiAnalysisRequestData> response = apiClient.makeGetRequest(
            "api.joseki.analysis_requests_next",
            null,
            queryString,
            JosekiAnalysisRequestData.class,
            props
        );
        if (response.getStatusCode() == 204) {
            return null;
        }
        if (response.getStatusCode() == 404) {
            String error = response.getErrorMessage();
            if (error != null && error.contains("No joseki analysis request found")) {
                return null;
            }
            throw new RuntimeException("Failed to fetch next joseki analysis request: HTTP 404 - " + error);
        }
        if (response.getStatusCode() == 401 || response.getStatusCode() == 403) {
            throw new SecurityException("Josekipedia rejected the analysis worker credentials: HTTP "
                + response.getStatusCode() + " - " + response.getErrorMessage());
        }
        if (!response.isSuccess()) {
            throw new RuntimeException("Failed to fetch next joseki analysis request: HTTP "
                + response.getStatusCode() + " - " + response.getErrorMessage());
        }
        return response.getData();
    }

    private ScheduledExecutorService startAnalysisHeartbeat(
        JosekiAnalysisRequestData request,
        AtomicBoolean leaseLost,
        AtomicLong lastHeartbeat
    ) {
        if (request.handleId == null) {
            return null;
        }

        ApiClient heartbeatClient = new ApiClient();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "joseki-analysis-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(() -> {
            try {
                Map<String, String> pathParams = Map.of(
                    "id", String.valueOf(request.id),
                    "handleId", String.valueOf(request.handleId)
                );
                ApiClient.ApiResponse<Object> response = heartbeatClient.makePostRequest(
                    "api.joseki.analysis_request_heartbeat",
                    pathParams,
                    "{}",
                    Object.class,
                    props
                );
                if (response.isSuccess()) {
                    lastHeartbeat.set(System.currentTimeMillis());
                } else if (response.getStatusCode() == 403 || response.getStatusCode() == 404
                    || response.getStatusCode() == 409) {
                    leaseLost.set(true);
                    System.out.println(RED + "Joseki analysis request " + request.id
                        + " lost its handle: HTTP " + response.getStatusCode() + RESET);
                } else {
                    System.out.println(RED + "Joseki analysis request " + request.id
                        + " heartbeat failed: HTTP " + response.getStatusCode() + RESET);
                }
            } catch (Exception ex) {
                System.out.println(RED + "Joseki analysis request " + request.id
                    + " heartbeat failed: " + describeException(ex) + RESET);
            }
        }, 5L, 5L, TimeUnit.SECONDS);
        return executor;
    }

    private JosekiNodeEntry resolveNodeForAnalysis(String path) throws Exception {
        JosekiNodeEntry entry = fetchNodeByPath(path);
        if (entry != null) {
            return entry;
        }

        entry = new JosekiNodeEntry();
        entry.path = path;
        String parentPath = parentPath(path);
        if (parentPath != null) {
            JosekiNodeEntry parent = fetchNodeByPath(parentPath);
            entry.parentId = parent == null ? null : parent.id;
        }
        return entry;
    }

    private JosekiNodeEntry fetchNodeByPath(String path) throws Exception {
        List<String> params = new ArrayList<>();
        addQueryParam(params, "path", path);
        addQueryParam(params, "limit", "1");
        String queryString = "?" + String.join("&", params);
        ApiClient.ApiResponse<JosekiNodeListResponse> response = apiClient.makeGetRequest(
            "api.joseki.nodes",
            null,
            queryString,
            JosekiNodeListResponse.class,
            props
        );
        if (!response.isSuccess()) {
            throw new RuntimeException("Failed to fetch joseki node for path " + formatPath(path)
                + ": HTTP " + response.getStatusCode() + " - " + response.getErrorMessage());
        }

        JosekiNodeListResponse page = response.getData();
        List<JosekiNodeEntry> entries = page == null || page.entries == null
            ? List.of()
            : page.entries;
        if (entries.isEmpty()) {
            return null;
        }
        if (entries.size() != 1 || !path.equals(entries.get(0).path)) {
            throw new IllegalStateException("Unexpected joseki node response for path " + formatPath(path));
        }
        return entries.get(0);
    }

    private void reportAnalysisRequestError(int requestId, String message) {
        try {
            Map<String, String> pathParams = new HashMap<>();
            pathParams.put("id", String.valueOf(requestId));
            Map<String, String> body = new HashMap<>();
            body.put("message", message.length() <= 2000 ? message : message.substring(0, 2000));
            ApiClient.ApiResponse<Object> response = apiClient.makePostRequest(
                "api.joseki.analysis_request_error",
                pathParams,
                gson.toJson(body),
                Object.class,
                props
            );
            if (!response.isSuccess()) {
                System.out.println(RED + "Failed to report joseki analysis request " + requestId
                    + " error: HTTP " + response.getStatusCode()
                    + " - " + response.getErrorMessage() + RESET);
            }
        } catch (Exception ex) {
            System.out.println(RED + "Failed to report joseki analysis request " + requestId
                + " error: " + describeException(ex) + RESET);
        }
    }

    private void sleepForRequests(long pollIntervalMs) {
        try {
            Thread.sleep(pollIntervalMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private JosekiHumanPolicyTaskListResponse fetchMissingHumanPolicyNodes(int limit, int offset) throws Exception {
        String queryString = buildMissingHumanPolicyNodesQuery(limit, offset);
        System.out.println(GREEN + "Joseki Human Policy tasks API URL: "
            + apiClient.buildUrl("api.joseki.human_policy_tasks", null, queryString, props) + RESET);

        return withApiRetries("fetch joseki nodes missing human policy"
            + " offset=" + offset + " limit=" + limit, () -> {
                ApiClient.ApiResponse<JosekiHumanPolicyTaskListResponse> response = apiClient.makeGetRequest(
                    "api.joseki.human_policy_tasks", null, queryString, JosekiHumanPolicyTaskListResponse.class, props);
                if (!response.isSuccess()) {
                    throw new RuntimeException("Failed to fetch joseki Human Policy tasks: HTTP "
                        + response.getStatusCode() + " - " + response.getErrorMessage());
                }

                JosekiHumanPolicyTaskListResponse page = response.getData();
                if (page == null) {
                    page = new JosekiHumanPolicyTaskListResponse();
                    page.entries = List.of();
                }
                return page;
            });
    }

    private JosekiHumanPolicyTaskProgress fetchHumanPolicyTaskProgress() throws Exception {
        String queryString = buildHumanPolicyTaskProgressQuery();
        return withApiRetries("fetch joseki Human Policy task progress", () -> {
            ApiClient.ApiResponse<JosekiHumanPolicyTaskProgress> response = apiClient.makeGetRequest(
                "api.joseki.human_policy_progress",
                null,
                queryString,
                JosekiHumanPolicyTaskProgress.class,
                props
            );
            if (!response.isSuccess()) {
                throw new RuntimeException("Failed to fetch joseki Human Policy task progress: HTTP "
                    + response.getStatusCode() + " - " + response.getErrorMessage());
            }

            JosekiHumanPolicyTaskProgress progress = response.getData();
            return progress == null ? new JosekiHumanPolicyTaskProgress() : progress;
        });
    }

    private String buildMissingHumanPolicyNodesQuery(int limit, int offset) {
        List<String> params = new ArrayList<>();
        addHumanPolicyTaskFilters(params);
        addQueryParam(params, "limit", String.valueOf(limit));
        addQueryParam(params, "offset", String.valueOf(offset));
        addQueryParam(params, "sort", "breadth");

        return "?" + String.join("&", params);
    }

    private String buildHumanPolicyTaskProgressQuery() {
        List<String> params = new ArrayList<>();
        addHumanPolicyTaskFilters(params);

        return "?" + String.join("&", params);
    }

    private void addHumanPolicyTaskFilters(List<String> params) {
        addQueryParam(params, "humanPolicyProfiles", String.join(",", humanSLProfiles()));

        if (props.containsKey("path")) {
            addQueryParam(params, "path", props.getProperty("path", ""));
        } else {
            addOptionalQueryParam(params, "pathPrefix");
        }
        addOptionalQueryParam(params, "includeDeleted");
    }

    private Set<String> fetchChildMoves(int parentId) throws Exception {
        if (parentId <= 0) {
            return Set.of();
        }
        if (childMovesByParentId.containsKey(parentId)) {
            return childMovesByParentId.get(parentId);
        }

        Set<String> childMoves = new LinkedHashSet<>();
        int offset = 0;
        while (true) {
            JosekiNodeListResponse page = fetchChildNodePage(parentId, NODE_PAGE_LIMIT, offset);
            List<JosekiNodeEntry> entries = page.entries == null ? List.of() : page.entries;
            for (JosekiNodeEntry entry : entries) {
                String move = lastMove(entry.path);
                if (move != null) {
                    childMoves.add(move);
                }
            }

            if (entries.isEmpty() || offset + entries.size() >= page.totalRecords) {
                break;
            }
            offset += entries.size();
        }

        childMovesByParentId.put(parentId, childMoves);
        return childMoves;
    }

    private JosekiNodeListResponse fetchChildNodePage(int parentId, int limit, int offset) throws Exception {
        List<String> params = new ArrayList<>();
        addQueryParam(params, "parentId", String.valueOf(parentId));
        addQueryParam(params, "limit", String.valueOf(limit));
        addQueryParam(params, "offset", String.valueOf(offset));
        String queryString = "?" + String.join("&", params);

        return withApiRetries("fetch joseki child nodes parent=" + parentId
            + " offset=" + offset + " limit=" + limit, () -> {
                ApiClient.ApiResponse<JosekiNodeListResponse> response = apiClient.makeGetRequest(
                    "api.joseki.nodes", null, queryString, JosekiNodeListResponse.class, props);
                if (!response.isSuccess()) {
                    throw new RuntimeException("Failed to fetch joseki child nodes for parent " + parentId + ": HTTP "
                        + response.getStatusCode() + " - " + response.getErrorMessage());
                }

                JosekiNodeListResponse page = response.getData();
                if (page == null) {
                    page = new JosekiNodeListResponse();
                    page.entries = List.of();
                }
                return page;
            });
    }

    String buildNodesQuery(
        int limit, int offset, String scope, boolean passAnalysisMissing
    ) {
        List<String> params = new ArrayList<>();
        addQueryParam(params, "scope", scope);
        if (passAnalysisMissing) {
            addQueryParam(params, "passAnalysisMissing", "true");
        } else if (!forceRecalculate()) {
            addQueryParam(params, "analysisMissing", "true");
        }
        addQueryParam(params, "limit", String.valueOf(limit));
        addQueryParam(params, "offset", String.valueOf(offset));

        if (props.containsKey("path")) {
            addQueryParam(params, "path", props.getProperty("path", ""));
        } else {
            addOptionalQueryParam(params, "pathPrefix");
        }
        addOptionalQueryParam(params, "includeDeleted");
        addOptionalQueryParam(params, "sort");

        return "?" + String.join("&", params);
    }

    private boolean forceRecalculate() {
        return Boolean.parseBoolean(props.getProperty("force", "false"));
    }

    private boolean humanPolicyOnly() {
        return Boolean.parseBoolean(props.getProperty("humanPolicyOnly", "false"));
    }

    private boolean passOnly() {
        return Boolean.parseBoolean(props.getProperty("passOnly", "false"));
    }

    private <T> T withApiRetries(String operation, ThrowingSupplier<T> supplier) throws Exception {
        int maxAttempts = apiMaxAttempts();
        long retryDelayMs = apiRetryDelayMs();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return supplier.get();
            } catch (Exception ex) {
                if (attempt >= maxAttempts) {
                    throw ex;
                }

                System.out.println(YELLOW + "Joseki API " + operation
                    + " failed on attempt " + attempt + "/" + maxAttempts
                    + ": " + describeException(ex)
                    + "; retrying in " + retryDelayMs + "ms"
                    + RESET);
                sleepBeforeRetry(operation, retryDelayMs);
            }
        }

        throw new RuntimeException("Joseki API " + operation + " failed without returning a result");
    }

    private void sleepBeforeRetry(String operation, long retryDelayMs) {
        if (retryDelayMs <= 0) {
            return;
        }

        try {
            Thread.sleep(retryDelayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while retrying joseki API " + operation, ex);
        }
    }

    private int apiMaxAttempts() {
        return Math.max(1, Integer.parseInt(props.getProperty(
            "joseki.api_max_attempts",
            String.valueOf(DEFAULT_API_MAX_ATTEMPTS)
        )));
    }

    private long apiRetryDelayMs() {
        return Math.max(0L, Long.parseLong(props.getProperty(
            "joseki.api_retry_delay_ms",
            String.valueOf(DEFAULT_API_RETRY_DELAY_MS)
        )));
    }

    private String describeException(Exception ex) {
        return ex.getClass().getSimpleName()
            + (ex.getMessage() == null ? "" : " - " + ex.getMessage());
    }

    private List<AnalysisScope> analysisScopes() {
        String configured = props.getProperty("joseki.analysis_scopes", "local");
        List<AnalysisScope> scopes = new ArrayList<>();
        Set<AnalysisScope> seen = new LinkedHashSet<>();
        for (String rawScope : configured.split(",")) {
            if (rawScope.isBlank()) {
                continue;
            }

            AnalysisScope scope = AnalysisScope.fromConfig(rawScope);
            if (seen.add(scope)) {
                scopes.add(scope);
            }
        }

        if (scopes.isEmpty()) {
            scopes.add(AnalysisScope.LOCAL);
        }

        return scopes;
    }

    private String formatScopes(List<AnalysisScope> scopes) {
        return scopes.stream()
            .map(scope -> scope.value)
            .toList()
            .toString();
    }

    private void submitResults(
        List<JosekiAnalysisResultData> results,
        List<JosekiHumanPolicyDistributionData> humanPolicyDistributions
    ) throws Exception {
        submitResults(results, humanPolicyDistributions, null, "recalculate", null, null);
    }

    private void submitResults(
        List<JosekiAnalysisResultData> results,
        List<JosekiHumanPolicyDistributionData> humanPolicyDistributions,
        String humanPolicyStatus
    ) throws Exception {
        submitResults(
            results,
            humanPolicyDistributions,
            null,
            "recalculate",
            null,
            humanPolicyStatus
        );
    }

    private void submitResults(
        List<JosekiAnalysisResultData> results,
        List<JosekiHumanPolicyDistributionData> humanPolicyDistributions,
        Integer requestId,
        String source,
        Long durationMs,
        String humanPolicyStatus
    ) throws Exception {
        JosekiAnalysisSubmitBody body = new JosekiAnalysisSubmitBody();
        body.requestId = requestId;
        body.durationMs = durationMs;
        body.source = source;
        body.clientVersion = CLIENT_VERSION;
        body.results = results;
        body.humanPolicyDistributions = humanPolicyDistributions;

        String path = !results.isEmpty()
            ? results.get(0).path
            : (!humanPolicyDistributions.isEmpty() ? humanPolicyDistributions.get(0).path : null);
        String pathLabel = path == null ? "<none>" : formatPath(path);
        String requestBody = gson.toJson(body);
        if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
            System.out.println("Submitting joseki recalculation JSON: " + requestBody);
        }
        String humanPolicyStatusLabel = humanPolicyStatus == null || humanPolicyStatus.isBlank()
            ? ""
            : " (" + humanPolicyStatus + ")";
        if (results.isEmpty()) {
            System.out.println("Submitting " + humanPolicyDistributions.size()
                + " Human Policy distributions" + humanPolicyStatusLabel
                + " for path=" + pathLabel);
        } else {
            System.out.println("Submitting " + results.size()
                + " joseki recalculated results + " + humanPolicyDistributions.size()
                + " Human Policy distributions" + humanPolicyStatusLabel
                + " for path=" + pathLabel);
        }

        withApiRetries("submit joseki analysis results path=" + pathLabel, () -> {
            ApiClient.ApiResponse<Object> response = apiClient.makePostRequest(
                "api.joseki.analysis_results", null, requestBody, Object.class, props);
            if (!response.isSuccess()) {
                throw new RuntimeException("Failed to submit joseki analysis result: HTTP "
                    + response.getStatusCode() + " - " + response.getErrorMessage());
            }
            return null;
        });
        for (JosekiAnalysisResultData result : results) {
            if (result.passAnalysis != null) {
                System.out.println("Submitted Pass analysis for path=" + formatPath(result.path));
            }
        }
    }

    private void submitPassAnalysisUpdate(JosekiPassAnalysisUpdateData update) throws Exception {
        JosekiAnalysisSubmitBody body = new JosekiAnalysisSubmitBody();
        body.source = "recalculate";
        body.clientVersion = CLIENT_VERSION;
        body.results = List.of();
        body.humanPolicyDistributions = List.of();
        body.passAnalysisUpdates = List.of(update);

        String pathLabel = formatPath(update.path);
        String requestBody = gson.toJson(body);
        withApiRetries("submit joseki pass analysis path=" + pathLabel, () -> {
            ApiClient.ApiResponse<Object> response = apiClient.makePostRequest(
                "api.joseki.analysis_results", null, requestBody, Object.class, props);
            if (!response.isSuccess()) {
                throw new RuntimeException("Failed to submit joseki pass analysis: HTTP "
                    + response.getStatusCode() + " - " + response.getErrorMessage());
            }
            return null;
        });
        System.out.println("Submitted Pass analysis for path=" + pathLabel);
    }

    private void addOptionalQueryParam(List<String> params, String key) {
        String value = props.getProperty(key);
        if (value != null && !value.isBlank()) {
            addQueryParam(params, key, value);
        }
    }

    private void addQueryParam(List<String> params, String key, String value) {
        params.add(URLEncoder.encode(key, StandardCharsets.UTF_8)
            + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private String katagoWeightsFile() {
        String model = requestModelPath == null
            ? props.getProperty("kata.model", "unknown")
            : requestModelPath;
        int slash = Math.max(model.lastIndexOf('/'), model.lastIndexOf('\\'));
        return slash >= 0 ? model.substring(slash + 1) : model;
    }

    private int configuredVisits() {
        return requestVisits == null
            ? Integer.parseInt(props.getProperty("joseki.visits", "1000"))
            : requestVisits;
    }

    private String resolveRequestModelPath(String requestedModel) {
        if (requestedModel == null) {
            return null;
        }
        if (!REQUEST_MODELS.contains(requestedModel)) {
            throw new IllegalArgumentException("Unsupported KataGo model: " + requestedModel);
        }
        Path configuredModel = Path.of(props.getProperty("kata.model")).toAbsolutePath();
        Path selectedModel = configuredModel.resolveSibling(requestedModel).normalize();
        if (!Files.isRegularFile(selectedModel)) {
            throw new IllegalArgumentException("KataGo model is unavailable on this worker: " + requestedModel);
        }
        return selectedModel.toString();
    }

    private String formatPath(String path) {
        return path == null || path.isBlank() ? "<root>" : path;
    }

    private String lastMove(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }

        String[] moves = path.split(",");
        for (int i = moves.length - 1; i >= 0; i--) {
            String move = moves[i].trim();
            if (!move.isEmpty()) {
                return "pass".equalsIgnoreCase(move) ? "pass" : move.toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    private String parentPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int separator = path.lastIndexOf(',');
        return separator < 0 ? "" : path.substring(0, separator);
    }

    private void printProgressBar(String stage, int current, int total, JosekiNodeEntry node) {
        printProgressBar(stage, current, total, node, null);
    }

    private void printProgressBar(
        String stage,
        int current,
        int total,
        JosekiNodeEntry node,
        Long startedAt
    ) {
        Long remainingMs = null;
        if (startedAt != null && current > 0 && total > current) {
            long elapsedMs = Math.max(1L, System.currentTimeMillis() - startedAt);
            remainingMs = Math.max(
                0L,
                Math.round(elapsedMs * (total - current) / (double) current)
            );
        }
        printProgressBar(stage, current, total, node, startedAt, remainingMs);
    }

    private void printProgressBar(
        String stage,
        int current,
        int total,
        JosekiNodeEntry node,
        Long startedAt,
        Long remainingMs
    ) {
        printProgressBar(stage, current, total, node, startedAt, remainingMs, null);
    }

    private void printProgressBar(
        String stage,
        int current,
        int total,
        JosekiNodeEntry node,
        Long startedAt,
        Long remainingMs,
        String detail
    ) {
        int safeTotal = Math.max(total, current);
        int percent = safeTotal == 0 ? 100 : (current * 100) / safeTotal;
        int barLength = 30;
        int filled = safeTotal == 0 ? barLength : (current * barLength) / safeTotal;

        StringBuilder bar = new StringBuilder();
        bar.append(CYAN).append(stage).append(" progress ")
            .append(current).append("/").append(safeTotal).append(" ");
        bar.append(GREEN).append("[");
        for (int i = 0; i < barLength; i++) {
            bar.append(i < filled ? "=" : " ");
        }
        bar.append("] ");
        bar.append(YELLOW).append(percent).append("%");
        bar.append(RESET);
        if (startedAt != null) {
            long elapsedMs = Math.max(1L, System.currentTimeMillis() - startedAt);
            bar.append(" elapsed=").append(formatDuration(elapsedMs));
        }
        if (remainingMs != null && safeTotal > current) {
            bar.append(" ETA~").append(formatDuration(remainingMs));
        }
        if (detail != null) {
            bar.append(detail);
        }
        if (node != null) {
            bar.append(" node=").append(node.id)
                .append(" depth=").append(pathDepth(node.path))
                .append(" path=").append(formatPath(node.path));
        }
        System.out.println(bar);
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
}
