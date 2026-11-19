package autoprob.analysis;

import autoprob.KataBrain;
import autoprob.joseki.TenukiMoves;
import autoprob.QueryBuilder;
import autoprob.api.JosekiAnalysisResultData;
import autoprob.api.JosekiAnalysisSubmitBody;
import autoprob.api.JosekiHumanPolicyDistributionData;
import autoprob.api.JosekiNodeEntry;
import autoprob.api.JosekiPassAnalysisUpdateData;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Computes a self-contained joseki snapshot supplied by the unified queue. */
public final class JosekiTaskAdapter {
    public static final int CLIENT_VERSION = 4;
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";
    private final DecimalFormat df = new DecimalFormat("0.00");
    private static final int ROOT_LOCAL_SIZE = 10;
    private final Properties props = new Properties();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<Path, String> analysisFileHashes = new HashMap<>();
    private int queryCounter;
    private String taskBaseSgf;
    private Set<String> taskChildMoves;
    private Set<String> taskParentChildMoves;
    private String requestModelPath;

    public JosekiTaskAdapter(Properties configured) {
        props.putAll(configured);
    }

    enum AnalysisScope {
        LOCAL("local", true, false),
        LOCAL_WITH_MOVES("local_with_moves", true, true),
        LOCAL_WITH_MOVES_NO_TENUKI("local_with_moves_no_tenuki", true, true),
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
                case "local_with_moves_no_tenuki" -> LOCAL_WITH_MOVES_NO_TENUKI;
                case "global" -> GLOBAL;
                default -> throw new IllegalArgumentException("Unknown joseki analysis scope: " + value);
            };
        }
    }

    /** Self-contained joseki calculation adapter; scheduling and submission belong to the queue. */
    public JosekiAnalysisSubmitBody analyzeTask(JsonObject payload, KataBrain brain) throws Exception {
        taskBaseSgf = payload.get("sgf").getAsString();
        Node base = new Parser().parse(taskBaseSgf).advance2end();
        if (base.getRoot().getXtra("KM") == null) {
            base.getRoot().setXtraTag("KM", props.getProperty("joseki.komi"));
            taskBaseSgf = "(" + base.getRoot().outputSGF(false) + ")";
        }
        if (base.board.boardX != 19 || base.board.boardY != 19) {
            throw new IllegalArgumentException("Joseki scoped analysis requires a 19x19 board");
        }
        requestModelPath = brain.modelPath;
        taskChildMoves = new LinkedHashSet<>(strings(payload, "childMoves"));
        taskParentChildMoves = new LinkedHashSet<>(strings(payload, "parentChildMoves"));
        List<AnalysisScope> scopes = payload.has("scopes")
            ? strings(payload, "scopes").stream().map(AnalysisScope::fromConfig).toList() : analysisScopes();
        JosekiNodeEntry entry = new JosekiNodeEntry();
        entry.path = payload.has("path") ? payload.get("path").getAsString() : "";
        JosekiAnalysisSubmitBody result = new JosekiAnalysisSubmitBody();
        result.clientVersion = CLIENT_VERSION;
        boolean partialFill = payload.has("includePass");
        result.results = analyzeNode(entry, brain, scopes, !partialFill);
        if (partialFill && payload.get("includePass").getAsBoolean()) {
            Double globalScore = result.results.stream().filter(value -> "global".equals(value.scope))
                .map(value -> value.score).findFirst().orElse(null);
            result.passAnalysisUpdates = List.of(analyzePassNode(entry, brain, globalScore));
        }
        boolean includeHumanPolicy = payload.has("humanPolicyProfiles")
            || payload.has("includeHumanPolicy") && payload.get("includeHumanPolicy").getAsBoolean();
        List<String> profiles = payload.has("humanPolicyProfiles") ? strings(payload, "humanPolicyProfiles")
            : includeHumanPolicy ? humanSLProfiles() : List.of();
        result.humanPolicyDistributions = analyzeHumanPolicies(entry, brain, profiles);
        if (partialFill && result.humanPolicyDistributions.size() != profiles.size()) {
            throw new IllegalStateException("Could not calculate every missing Human Policy profile");
        }
        return result;
    }

    private List<String> strings(JsonObject payload, String key) {
        if (!payload.has(key) || !payload.get(key).isJsonArray()) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement value : payload.getAsJsonArray(key)) values.add(value.getAsString());
        return values;
    }

    private record ScopedQueryResult(KataAnalysisResult analysis, double policyMass, String configHash) {}
    private final Map<String, ScopedQueryResult> scopedQueryCache = new HashMap<>();

    private List<JosekiAnalysisResultData> analyzeNode(
        JosekiNodeEntry entry,
        KataBrain brain,
        List<AnalysisScope> scopes,
        boolean includePass
    ) throws Exception {
        scopedQueryCache.clear();
        List<JosekiAnalysisResultData> results = new ArrayList<>();
        for (AnalysisScope scope : scopes) {
            results.add(analyzeNode(entry, brain, scope));
        }
        if (!includePass) return results;
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
        return results;
    }

    private JosekiPassAnalysisUpdateData analyzePassNode(
        JosekiNodeEntry entry, KataBrain brain, Double currentGlobalScore
    ) throws Exception {
        scopedQueryCache.clear();
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
        Set<String> currentChildMoves = !scope.includeExistingMoves ? Set.of()
            : taskChildMoves;
        Set<String> parentChildMoves = new LinkedHashSet<>();
        String nodeMove = lastMove(path);

        if (scope.includeExistingMoves && node.mom != null) {
            parentChildMoves = new LinkedHashSet<>(taskParentChildMoves);
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
            + " score=" + df.format(result.score)
            + " loss=" + df.format(result.loss)
            + (result.prior == null ? "" : " prior=" + df.format(result.prior * 1000.0))
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

        ScopedQueryResult cached = scopedQueryCache.get(configHash);
        if (cached != null) return cached;
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
        ScopedQueryResult scopedResult = new ScopedQueryResult(result, policyMass, configHash);
        scopedQueryCache.put(configHash, scopedResult);
        return scopedResult;
    }

    KataQuery buildScopedQuery(Node node, AnalysisScope scope, Set<String> childMoves) {
        KataQuery query = new QueryBuilder().buildQuery(node);
        query.includePolicy = true;
        query.analyzeTurns.clear();
        query.analyzeTurns.add(0);
        query.maxVisits = configuredVisits();
        Set<String> scopedChildMoves = scope.includeExistingMoves ? childMoves : Set.of();
        if (scope == AnalysisScope.LOCAL_WITH_MOVES_NO_TENUKI) {
            scopedChildMoves = TenukiMoves.withoutTenuki(node, scopedChildMoves);
        }

        int maxMoveDistance = Integer.parseInt(props.getProperty("joseki.max_move_distance"));
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

        String config = "joseki-katago-query-v2\n"
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
        int maxMoveDistance = Integer.parseInt(props.getProperty("joseki.max_move_distance"));
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
        query.maxVisits = Integer.parseInt(props.getProperty("joseki.human_visits"));

        KataQuery.OverrideSettings settings = new KataQuery.OverrideSettings();
        settings.humanSLProfile = profile;
        settings.ignorePreRootHistory = false;
        int symmetries = Integer.parseInt(props.getProperty("joseki.human_sl_symmetries"));
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
        return Arrays.stream(props.getProperty("joseki.human_sl_profiles").split(","))
            .map(String::trim).filter(value -> !value.isEmpty()).toList();
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
        return new Parser().parse(taskBaseSgf).advance2end();
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

    private List<AnalysisScope> analysisScopes() {
        String configured = props.getProperty("joseki.analysis_scopes");
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

    private String describeException(Exception error) {
        return error.getClass().getSimpleName()
            + (error.getMessage() == null ? "" : " - " + error.getMessage());
    }

    private String katagoWeightsFile() {
        String model = requestModelPath == null
            ? props.getProperty("kata.model", "unknown")
            : requestModelPath;
        int slash = Math.max(model.lastIndexOf('/'), model.lastIndexOf('\\'));
        return slash >= 0 ? model.substring(slash + 1) : model;
    }

    private int configuredVisits() {
        return Integer.parseInt(props.getProperty("joseki.visits"));
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

}
