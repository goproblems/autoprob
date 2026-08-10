package autoprob.joseki;

import autoprob.ApiClient;
import autoprob.KataBrain;
import autoprob.QueryBuilder;
import autoprob.api.JosekiAnalysisResultData;
import autoprob.api.JosekiAnalysisSubmitBody;
import autoprob.api.JosekiHumanPolicyDistributionData;
import autoprob.api.JosekiHumanPolicyTaskListResponse;
import autoprob.api.JosekiHumanPolicyTaskProgress;
import autoprob.api.JosekiNodeEntry;
import autoprob.api.JosekiNodeListResponse;
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

import java.awt.Point;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.HashSet;
import java.util.HexFormat;

public class JosekiNodeRecalculator {
    public static final int CLIENT_VERSION = 4;
    private static final int HUMAN_POLICY_NORMALIZATION_VERSION = 1;

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
    private String cachedHumanModelIdentity = null;
    private int queryCounter = 0;

    private enum AnalysisScope {
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
    }

    public void run() throws Exception {
        boolean humanPolicyOnly = humanPolicyOnly();
        boolean force = forceRecalculate();
        List<AnalysisScope> scopes = humanPolicyOnly ? List.of() : analysisScopes();
        if (humanPolicyOnly) {
            if (humanModelIdentity() == null) {
                throw new IllegalArgumentException(
                    "humanPolicyOnly=true requires kata.human_model to be configured"
                );
            }
            System.out.println("Filling only missing human policies in breadth-first order"
                + " (limit=" + NODE_PAGE_LIMIT + ")");
        } else {
            System.out.println(force
                ? "Filling missing human policies, then force recalculating joseki nodes, ignoring existing analysis client version for scopes "
                    + formatScopes(scopes) + " (limit=" + NODE_PAGE_LIMIT + ")"
                : "Filling missing human policies, then recalculating joseki nodes below client version " + CLIENT_VERSION + " for scopes "
                    + formatScopes(scopes) + " (limit=" + NODE_PAGE_LIMIT + ")");
        }

        int processedNodes = 0;
        int submittedResults = 0;
        int submittedHumanPolicies = 0;
        int failedNodes = 0;
        Set<Integer> processedNodeIds = new HashSet<>();

        KataBrain brain = new KataBrain(props);
        try {
            if (humanPolicyOnly) {
                RecalculationResult humanPolicyResult = processMissingHumanPolicies(brain, new HashSet<>());
                processedNodes += humanPolicyResult.processedNodes();
                submittedHumanPolicies += humanPolicyResult.submittedHumanPolicies();
                failedNodes += humanPolicyResult.failedNodes();
            } else {
                RecalculationResult humanPolicyResult = processMissingHumanPolicies(brain, new HashSet<>());
                processedNodes += humanPolicyResult.processedNodes();
                submittedHumanPolicies += humanPolicyResult.submittedHumanPolicies();
                failedNodes += humanPolicyResult.failedNodes();

                List<AnalysisScope> candidateScopes = force ? List.of(scopes.get(0)) : scopes;
                for (AnalysisScope candidateScope : candidateScopes) {
                    RecalculationResult result = processScope(brain, candidateScope, scopes, force, processedNodeIds);
                    processedNodes += result.processedNodes();
                    submittedResults += result.submittedResults();
                    submittedHumanPolicies += result.submittedHumanPolicies();
                    failedNodes += result.failedNodes();
                }
            }
        } finally {
            brain.stopKataBrain();
        }

        System.out.println("Joseki recalculation complete. Processed " + processedNodes
            + " nodes, submitted " + submittedResults + " results + " + submittedHumanPolicies
            + " human policies, failed " + failedNodes + " nodes.");
    }

    private record ProcessNodesResult(int submittedResults, int submittedHumanPolicies, int failedNodes) {}

    private record ScopedQueryResult(KataAnalysisResult analysis, double policyMass) {}

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
        Set<Integer> processedNodeIds
    ) throws Exception {
        System.out.println("Starting joseki recalculation candidate scope=" + candidateScope.value
            + ", analysis scopes=" + formatScopes(analysisScopes));

        int submittedResults = 0;
        int submittedHumanPolicies = 0;
        int failedNodes = 0;
        int queryOffset = 0;
        int processedNodesBefore = processedNodeIds.size();

        while (true) {
            JosekiNodeListResponse page = fetchNodes(NODE_PAGE_LIMIT, queryOffset, candidateScope);
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
                processedNodeIds,
                totalNodesEstimate
            );
            submittedResults += result.submittedResults();
            submittedHumanPolicies += result.submittedHumanPolicies();
            failedNodes += result.failedNodes();

            System.out.println("Joseki recalculation candidate scope=" + candidateScope.value
                + ": processed " + processedNodeIds.size()
                + " nodes, submitted " + submittedResults + " analysis results + "
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
        int totalTasksEstimate = Math.max(0, progress.pendingNodes);
        System.out.println("Human Policy tasks this run: 0/" + totalTasksEstimate
            + " missing at start"
            + " (completed=" + progress.completedNodes
            + ", pruned=" + progress.prunedNodes
            + ", ready=" + progress.readyNodes
            + ", blocked=" + progress.blockedNodes + ").");

        while (true) {
            JosekiHumanPolicyTaskListResponse page = fetchMissingHumanPolicyNodes(NODE_PAGE_LIMIT, queryOffset);
            List<JosekiNodeEntry> entries = page.entries == null ? List.of() : page.entries;
            if (entries.isEmpty()) {
                System.out.println("No more joseki nodes missing human policy.");
                break;
            }

            sortEntriesByBreadth(entries);
            int before = processedNodeIds.size();
            for (JosekiNodeEntry entry : entries) {
                if (!processedNodeIds.add(entry.id)) {
                    continue;
                }

                String path = entry.path == null ? "" : entry.path;
                try {
                    printProgressBar(
                        processedNodeIds.size() - processedNodesBefore,
                        totalTasksEstimate,
                        entry
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
            int processedThisRun = processedNodeIds.size() - processedNodesBefore;
            totalTasksEstimate = Math.max(
                processedThisRun,
                processedThisRun + Math.max(0, progress.pendingNodes)
            );
            System.out.println("Missing human policy pass: processed " + processedNodeIds.size()
                + " nodes, submitted " + submittedHumanPolicies + " human policies, failed " + failedNodes
                + " nodes. Refreshed progress=" + processedThisRun + "/" + totalTasksEstimate
                + " (completed=" + progress.completedNodes
                + ", pruned=" + progress.prunedNodes
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

    private ProcessNodesResult processNodes(List<JosekiNodeEntry> entries, KataBrain brain,
                                            AnalysisScope candidateScope, List<AnalysisScope> analysisScopes,
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
                printProgressBar(processedNodeIds.size(), totalNodesEstimate, entry);
                System.out.println("Recalculating joseki node " + entry.id
                    + " candidateScope=" + candidateScope.value
                    + " analysisScopes=" + formatScopes(analysisScopes)
                    + " path=" + formatPath(path)
                    + " version=" + entry.analysisClientVersion);

                List<JosekiAnalysisResultData> results = analyzeNode(entry, brain, analysisScopes);
                if (!results.isEmpty()) {
                    submitResults(results, List.of());
                }
                submittedResults += results.size();
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
        List<JosekiAnalysisResultData> results = new ArrayList<>();
        for (AnalysisScope scope : scopes) {
            results.add(analyzeNode(entry, brain, scope));
        }
        return results;
    }

    private JosekiAnalysisResultData analyzeNode(
        JosekiNodeEntry entry,
        KataBrain brain,
        AnalysisScope scope
    ) throws Exception {
        String path = entry.path == null ? "" : entry.path;
        Node node = buildNode(path);
        Set<String> currentChildMoves = scope.includeExistingMoves ? fetchChildMoves(entry.id) : Set.of();
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
        result.katagoPlayouts = Integer.parseInt(props.getProperty("joseki.visits", "1000"));
        result.katagoWeightsFile = katagoWeightsFile();
        result.prior = moveInfo == null ? null : moveInfo.prior;
        result.policyMass = currentQueryResult.policyMass();
        result.visits = moveInfo == null ? null : moveInfo.visits;
        result.moveOrder = moveInfo == null ? null : moveInfo.order;
        result.extraInfo = buildExtraInfo(scope, parentScore, score, moverScoreDelta);
        result.analysis = gson.toJson(currentResult);

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
        QueryBuilder queryBuilder = new QueryBuilder();
        KataQuery query = queryBuilder.buildQuery(node);
        query.id = "joseki:" + (++queryCounter) + ":" + role + ":" + scope.value;
        query.includePolicy = true;
        query.analyzeTurns.clear();
        query.analyzeTurns.add(0);
        query.maxVisits = Integer.parseInt(props.getProperty("joseki.visits", "1000"));

        Set<String> allowedMoves = null;
        int maxMoveDistance = Integer.parseInt(props.getProperty("joseki.max_move_distance", "4"));
        if (scope.restrictToNearbyMoves && maxMoveDistance >= 0) {
            allowedMoves = nearbyMoves(node, maxMoveDistance, childMoves);
            restrictToMoves(node, query, allowedMoves);
        }

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
        return new ScopedQueryResult(result, policyMass);
    }

    private List<JosekiHumanPolicyDistributionData> analyzeHumanPolicies(
        JosekiNodeEntry entry,
        KataBrain brain,
        List<String> profiles
    ) {
        List<JosekiHumanPolicyDistributionData> distributions = new ArrayList<>();
        String humanModel = humanModelIdentity();
        if (humanModel == null || humanModel.isBlank()) {
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
                distribution.humanModel = humanModel;
                distribution.distribution = new ArrayList<>(result.humanPolicy);
                distribution.localPolicyMass = calculatePolicyMass(result.humanPolicy, localMoves);
                distribution.maxMoveDistance = maxMoveDistance;
                distribution.normalizationVersion = HUMAN_POLICY_NORMALIZATION_VERSION;
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

    private String humanModelIdentity() {
        if (cachedHumanModelIdentity != null) {
            return cachedHumanModelIdentity;
        }

        String model = props.getProperty("kata.human_model");
        if (model == null || model.isBlank()) {
            return null;
        }

        try {
            Path modelPath = Path.of(model);
            if (!Files.isRegularFile(modelPath)) {
                throw new IllegalStateException("Human model file does not exist: " + modelPath);
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, "human-policy-query-v1");
            updateDigest(digest, "humanVisits=" + props.getProperty("joseki.human_visits", "1"));
            updateDigest(digest, "humanSymmetries=" + props.getProperty("joseki.human_sl_symmetries", "2"));
            updateDigest(digest, "komi=" + props.getProperty("joseki.komi", "6.5"));
            updateDigest(digest, "ignorePreRootHistory=false");
            updateDigestWithFile(digest, modelPath);
            updateDigestWithOptionalFile(digest, props.getProperty("kata.config"));
            updateDigestWithOptionalFile(digest, props.getProperty("joseki.base_sgf"));

            String fileName = modelPath.getFileName().toString();
            if (fileName.length() > 180) {
                fileName = fileName.substring(0, 180);
            }
            cachedHumanModelIdentity = fileName + "@" + HexFormat.of().formatHex(digest.digest());
            return cachedHumanModelIdentity;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to calculate Human model identity", ex);
        }
    }

    private void updateDigest(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private void updateDigestWithOptionalFile(MessageDigest digest, String fileName) throws Exception {
        if (fileName == null || fileName.isBlank()) {
            updateDigest(digest, "<none>");
            return;
        }
        updateDigestWithFile(digest, Path.of(fileName));
    }

    private void updateDigestWithFile(MessageDigest digest, Path path) throws Exception {
        updateDigest(digest, path.getFileName().toString());
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
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

    private JosekiNodeListResponse fetchNodes(int limit, int offset, AnalysisScope scope) throws Exception {
        String queryString = buildNodesQuery(limit, offset, scope);
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
        String humanModel = humanModelIdentity();
        if (humanModel != null) {
            addQueryParam(params, "humanPolicyModel", humanModel);
        }
        addQueryParam(params, "policyThreshold", String.valueOf(lowPolicyThreshold()));

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

    private String buildNodesQuery(int limit, int offset, AnalysisScope scope) {
        List<String> params = new ArrayList<>();
        addQueryParam(params, "scope", scope.value);
        if (!forceRecalculate()) {
            addQueryParam(params, "clientVersionLessThan", String.valueOf(CLIENT_VERSION));
        }
        addQueryParam(params, "policyThreshold", String.valueOf(lowPolicyThreshold()));
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

    private double lowPolicyThreshold() {
        double threshold = Double.parseDouble(props.getProperty("joseki.low_policy_threshold", "0.01"));
        if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException(
                "joseki.low_policy_threshold must be between 0 and 1"
            );
        }
        return threshold;
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
        JosekiAnalysisSubmitBody body = new JosekiAnalysisSubmitBody();
        body.source = "recalculate";
        body.clientVersion = CLIENT_VERSION;
        body.lowPolicyThreshold = lowPolicyThreshold();
        body.results = results;
        body.humanPolicyDistributions = humanPolicyDistributions;

        String path = !results.isEmpty()
            ? results.get(0).path
            : (!humanPolicyDistributions.isEmpty() ? humanPolicyDistributions.get(0).path : null);
        String pathLabel = path == null ? "<none>" : formatPath(path);
        String requestBody = gson.toJson(body);
        if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
            System.out.println("Submitting joseki recalculation JSON: " + requestBody);
        } else {
            System.out.println("Submitting " + results.size()
                + " joseki recalculated results + " + humanPolicyDistributions.size()
                + " Human Policy distributions for path=" + pathLabel);
        }

        withApiRetries("submit joseki recalculated results path=" + pathLabel, () -> {
            ApiClient.ApiResponse<Object> response = apiClient.makePostRequest(
                "api.joseki.analysis_results", null, requestBody, Object.class, props);
            if (!response.isSuccess()) {
                throw new RuntimeException("Failed to submit joseki recalculated result: HTTP "
                    + response.getStatusCode() + " - " + response.getErrorMessage());
            }
            return null;
        });
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
        String model = props.getProperty("kata.model", "unknown");
        int slash = Math.max(model.lastIndexOf('/'), model.lastIndexOf('\\'));
        return slash >= 0 ? model.substring(slash + 1) : model;
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

    private void printProgressBar(int current, int total, JosekiNodeEntry node) {
        int safeTotal = Math.max(total, current);
        int percent = safeTotal == 0 ? 100 : (current * 100) / safeTotal;
        int barLength = 30;
        int filled = safeTotal == 0 ? barLength : (current * barLength) / safeTotal;

        StringBuilder bar = new StringBuilder();
        bar.append(CYAN).append("Progress ").append(current).append("/").append(safeTotal).append(" ");
        bar.append(GREEN).append("[");
        for (int i = 0; i < barLength; i++) {
            bar.append(i < filled ? "=" : " ");
        }
        bar.append("] ");
        bar.append(YELLOW).append(percent).append("%");
        bar.append(RESET);
        if (node != null) {
            bar.append(" node=").append(node.id)
                .append(" path=").append(formatPath(node.path));
        }
        System.out.println(bar);
    }
}
