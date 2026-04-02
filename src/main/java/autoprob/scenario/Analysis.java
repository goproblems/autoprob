package autoprob.scenario;

import autoprob.KataBrain;
import autoprob.NodeAnalyzer;
import autoprob.api.AnalysisRequest;
import autoprob.api.AnalysisResult;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.parse.Parser;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;
import autoprob.katastruct.MoveInfo;
import com.google.gson.Gson;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Orchestrates running KataGo analysis for API scenarios.
 */
public class Analysis {
    public enum HumanLikeStyle {
        OBJECTIVE,
        HUMAN,
        // Ensuring all likely human moves are analyzed
        HUMAN_ANALYZED,
        // Heavily bias the search to anticipate human-like sequences rather than KataGo sequences.
        HUMAN_BIASED,
        // Bias the search to anticipate human-like sequences rather than KataGo sequences, but only for the opponent.
        HUMAN_OPPONENT_BIASED
    }

    private static final DecimalFormat df = new DecimalFormat("0.00");

    private static final String DEFAULT_HUMAN_RANK = "10k";
    private static final String VISITS_PROPERTY = "scenario.analysis.visits";
    private static final String FALLBACK_VISITS_PROPERTY = "search.visits";

    // Small offset added to depthFactor to prevent endness from being exactly 0
    private static final double DEPTH_FACTOR_OFFSET = 0.01;

    private final Properties props;
    private final KataBrain brain;
    private final Parser parser = new Parser();
    private final StringBuilder debugInfo = new StringBuilder();
    private final Gson gson = new Gson();

    private final AnalysisConfig defaultConfig;

    /**
     * Per-request configuration, may be overridden by scenario metadata.
     */
    private AnalysisConfig config;

    private ResultSubmitter resultSubmitter;

    @FunctionalInterface
    public interface ResultSubmitter {
        void submit(AnalysisResult[] results) throws Exception;
    }

    public Analysis(Properties props, KataBrain brain) throws Exception {
        this.props = Objects.requireNonNull(props, "props");
        this.brain = brain;
        this.defaultConfig = AnalysisConfig.fromProperties(props);
        this.config = defaultConfig;
    }

    private KataQuery.OverrideSettings buildOverrideSettings(String humanRank, HumanLikeStyle style) {
        KataQuery.OverrideSettings settings = new KataQuery.OverrideSettings();
        if (!humanRank.equals("max")) {
            settings.humanSLProfile = "preaz_" + humanRank;
        }

        switch (style) {
            case OBJECTIVE -> {
                settings.ignorePreRootHistory = true;
                settings.rootNumSymmetriesToSample = 1;
            }
            case HUMAN -> {
                // Normally analysis ignores history to be unbiased by move order, but humans definitely behave differently based on recent moves
                settings.ignorePreRootHistory = false;
                // Set rootNumSymmetriesToSample to 2, or to 8 instead of the default 1. This will slightly add latency but improve the quality of the human policy by averaging more symmetries, which might be good when relying so heavily on the raw human policy without any search.
                settings.rootNumSymmetriesToSample = 8;
            }
            case HUMAN_ANALYZED -> {
                // Set humanSLRootExploreProbWeightless to 0.5 (spend about 50% of playouts to explore human moves, in a weightless way that doesn't bias KataGo's evaluations).
                settings.humanSLRootExploreProbWeightless = 0.5;
                settings.humanSLRootExploreProbWeightful = 0.0;
                // Set humanSLCpuctPermanent to 2.0 or similar (when exploring human moves, ensure high-human-policy moves get many visits even if they lose a lot).
                // Set it to something lower if you want to reduce visits for moves that are judged to be very bad.
                settings.humanSLCpuctPermanent = 2.0;
                settings.humanSLPlaExploreProbWeightful = 0.0;
                settings.humanSLOppExploreProbWeightful = 0.0;
            }
            case HUMAN_BIASED -> {
                // Heavily bias the search to anticipate human-like sequences rather than KataGo sequences.
                // Set humanSLPlaExploreProbWeightful and humanSLOppExploreProbWeightful to 0.9 (spend about 90% of visits at every node using the human policy, in a weightful way that does bias KataGo's evaluations).
                settings.humanSLRootExploreProbWeightless = 0.0;
                settings.humanSLRootExploreProbWeightful = 0.5;
                settings.humanSLCpuctPermanent = 1.0;
                settings.humanSLPlaExploreProbWeightful = 0.9;
                settings.humanSLOppExploreProbWeightful = 0.9;
                settings.useUncertainty = false;
                settings.subtreeValueBiasFactor = 0.0;
                settings.useNoisePruning = false;
            }
            case HUMAN_OPPONENT_BIASED -> {
                // Bias the search to anticipate human-like sequences rather than KataGo sequences, but only for the opponent.
                // Set humanSLOppExploreProbWeightful to 0.8 (spend about 80% of visits at every node using the human policy, in a weightful way that does bias KataGo's values, but only for the opponent!).
                // Set useUncertainty to false and subtreeValueBiasFactor to 0.0 and useNoisePruning to false (important, disables a few search features that add strength but are highly likely to interfere with this kind of weightful biasing).
                // Set useNoisePruning to false is probably the most important of these - it adds the least strength in normal usage but might interfere the most. One could experiment with still enabling the other two for strength.
                settings.humanSLRootExploreProbWeightless = 0.0;
                settings.humanSLRootExploreProbWeightful = 0.0;
                settings.humanSLCpuctPermanent = 0.5;
                settings.humanSLPlaExploreProbWeightful = 0.0;
                settings.humanSLOppExploreProbWeightful = 0.8;
                settings.useUncertainty = false;
                settings.subtreeValueBiasFactor = 0.0;
                settings.useNoisePruning = false;
            }
        }

        System.out.println("OverrideSettings [style=" + style + ", rank=" + humanRank + "]: " + gson.toJson(settings));
        return settings;
    }

    public void setResultSubmitter(ResultSubmitter submitter) {
        this.resultSubmitter = submitter;
    }

    private void submitResults(AnalysisResult[] results) throws Exception {
        if (resultSubmitter != null && results.length > 0) {
            int maxRetries = 3;
            Exception lastException = null;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    resultSubmitter.submit(results);
                    return;
                } catch (Exception e) {
                    lastException = e;
                    System.err.println("Submit failed (attempt " + attempt + "/" + maxRetries + "): " + e.getMessage());
                    if (attempt < maxRetries) {
                        Thread.sleep(10000);
                    }
                }
            }
            throw lastException;
        }
    }

    /**
     * Runs KataGo on the supplied request path and summarizes the outcome.
     */
    public AnalysisResult[] analyze(AnalysisRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.scenario, "request.scenario");
        if (request.scenario.sgf == null || request.scenario.sgf.isBlank()) {
            throw new IllegalArgumentException("Scenario SGF is required");
        }

        // Build per-request config with metadata overrides and area constraints
        this.config = defaultConfig.withMetadata(request.scenario.metadata);
        if (request.scenario.metadata != null && request.scenario.metadata.configOverrides != null
                && !request.scenario.metadata.configOverrides.isEmpty()) {
            System.out.println("Applied config overrides from scenario metadata: " + request.scenario.metadata.configOverrides);
        }
        if (config.hasPlayerAreaConstraints() || config.hasComputerAreaConstraints()) {
            var md = config.metadata;
            System.out.println("Area constraints active — player: allowed=" + md.playerAllowedAreas
                + " disallowed=" + md.playerDisallowedAreas
                + ", computer: allowed=" + md.computerAllowedAreas
                + " disallowed=" + md.computerDisallowedAreas);
        }

        boolean isPrecalculationMode = request.isPrecalculate != null && request.isPrecalculate;

        if (isPrecalculationMode) {
            return analyzePrecalculation(request);
        }

        return analyzePath(request);
    }

    /**
     * Analyzes a specific path
     */
    private AnalysisResult[] analyzePath(AnalysisRequest request) throws Exception {
        var nodeAnalyzer = new NodeAnalyzer(props);

        Node root = parser.parse(request.scenario.sgf);
        applyKomiOverride(root);
        System.out.println(root.board); // draws the board out
        System.out.println("To move: " + (root.getToMove() == Intersection.BLACK ? "black" : "white"));

        int visits = determineVisits(request);
        System.out.println("Visits: " + visits);

        String humanRank = normalizeRank(request.difficulty);
        KataQuery.OverrideSettings overrideSettings = buildOverrideSettings(humanRank, HumanLikeStyle.HUMAN);

        // first we analyze the root position, establish a baseline for score and more
        KataAnalysisResult rootKata = nodeAnalyzer.analyzeNode(brain, root, visits, null, overrideSettings);

        // get last fragment for model
        String fullModelPath = props.getProperty("kata.model");
        String weightsFile = (fullModelPath.substring(fullModelPath.lastIndexOf('/') + 1)).substring(fullModelPath.lastIndexOf('\\') + 1);

        if (rootKata.isError()) {
            throw new RuntimeException("KataGo analysis error: " + rootKata.error);
        }

        // If path is empty, only analyze root node
        if (request.path == null || request.path.isEmpty()) {
            System.out.println("Empty path - analyzing root node only");
            AnalysisResult rootResult = buildRootAnalysisResult(request.difficulty, rootKata, weightsFile, visits);
            rootResult.isAnalyzed = true;

            AnalysisResult[] resultArray = new AnalysisResult[] { rootResult };
            submitResults(resultArray);
            return resultArray;
        }

        // play the moves in the path, get a new position from that
        Node node = addPath(root, request.path);
        if (Boolean.parseBoolean(props.getProperty("scenario.output_path_sgf", "false"))) {
            String sgf = "(" + root.outputSGF(true) + ")";
            System.out.println(sgf); // draws the board out
        }

        // analyze the parent node, so we know direct loss for the last move
        KataAnalysisResult momKata = nodeAnalyzer.analyzeNode(brain, node.mom, visits, null, overrideSettings);

        if (momKata.isError()) {
            throw new RuntimeException("KataGo analysis error: " + momKata.error);
        }

        // analyze the end position after the path
        KataAnalysisResult endKata = nodeAnalyzer.analyzeNode(brain, node, visits, null, overrideSettings);

        if (endKata.isError()) {
            throw new RuntimeException("KataGo analysis error: " + endKata.error);
        }

        node.kres = endKata;

        AnalysisResult result = buildAnalysisResult(request.path, request.difficulty, node,
            endKata, momKata, root, rootKata, weightsFile, 0.0, visits);
        result.analysis = gson.toJson(endKata);
        result.extraInfo = debugInfo.toString();
        result.isAnalyzed = true;

        if (Boolean.parseBoolean(props.getProperty("scenario.print_debug_info", "true"))) {
            System.out.println("dbg: " + debugInfo);
        }

        // make extendable list of possible results
        ArrayList<AnalysisResult> results = new ArrayList<>();

        // Add root result if no analyzed node for this difficulty
        boolean rootAnalyzed = false;
        if (request.scenario.analyzedRootDifficulties != null) {
            for (String analyzedDifficulty : request.scenario.analyzedRootDifficulties) {
                if (analyzedDifficulty.equals(request.difficulty)) {
                    rootAnalyzed = true;
                    break;
                }
            }
        }
        if (!rootAnalyzed) {
            System.out.println("Adding root result (No analyzed difficulty=" + request.difficulty + " node found)");
            AnalysisResult rootResult = buildRootAnalysisResult(request.difficulty, rootKata, weightsFile, visits);
            rootResult.isAnalyzed = true;
            results.add(rootResult);
        }

        results.add(result);

        // Determine if current move is human move or computer move
        boolean isHumanMove = (node.getToMove() != root.getToMove());

        // Add optimal moves from parent node if enabled
        if (config.includeOptimalMoves) {
            addOptimalMoves(node.mom, momKata, root, rootKata, request.path, request.difficulty, weightsFile, results);
        }

        // if not an end move, we can add possible response moves from katago
        // Only add response moves for HUMAN moves
        if (isHumanMove && result.endness < 0) {
            if (humanRank.equals("max")) {
                addResponseResultsMaxRank(brain, node, root, rootKata, result, results, endKata, humanRank);
            }
            else {
                addResponseResultsHumanRank(brain, node, root, rootKata, result, results, endKata, humanRank);
            }
        }

        AnalysisResult[] resultArray = results.toArray(AnalysisResult[]::new);
        submitResults(resultArray);
        return resultArray;
    }

    /**
     * Node in the precalculation queue.
     */
    private record PrecalcNode(Node node, KataAnalysisResult kata, String path, int depth) {}

    /**
     * Precalculation mode: precalculate the game tree from root or a specified path.
     * Uses BFS or DFS (configurable) to analyze nodes.
     * Submits results in batches if a ResultSubmitter is configured.
     *
     * @param request The analysis request with depth and maxNodes parameters
     * @return Array of AnalysisResult for all precalculated nodes
     */
    private AnalysisResult[] analyzePrecalculation(AnalysisRequest request) throws Exception {
        var nodeAnalyzer = new NodeAnalyzer(props);

        Node root = parser.parse(request.scenario.sgf);
        applyKomiOverride(root);
        System.out.println(root.board);
        System.out.println("To move: " + (root.getToMove() == Intersection.BLACK ? "black" : "white"));

        String startPath = (request.path != null && !request.path.isBlank()) ? request.path : "";
        int startDepth = startPath.isEmpty() ? 0 : startPath.split(",").length;

        System.out.println("Precalculation mode: strategy=" + (config.precalculationDepthFirst ? "dfs" : "bfs") +
            ", startPath=" + (startPath.isEmpty() ? "(root)" : startPath) +
            ", maxDepth=" + config.precalculationMaxDepth +
            ", maxNodes=" + config.precalculationMaxNodes + ", batchSize=" + config.precalculationBatchSize);

        int visits = determineVisits(request);
        String humanRank = normalizeRank(request.difficulty);
        KataQuery.OverrideSettings overrideSettings = buildOverrideSettings(humanRank, HumanLikeStyle.HUMAN);
        int maxDepth = config.precalculationMaxDepth;
        int maxNodes = config.precalculationMaxNodes;

        String fullModelPath = props.getProperty("kata.model");
        String weightsFile = (fullModelPath.substring(fullModelPath.lastIndexOf('/') + 1))
            .substring(fullModelPath.lastIndexOf('\\') + 1);

        ArrayList<AnalysisResult> results = new ArrayList<>();
        int nodesCount = 0;

        // Analyze root
        KataAnalysisResult rootKata = nodeAnalyzer.analyzeNode(brain, root, visits, null, overrideSettings);
        root.kres = rootKata;

        AnalysisResult rootResult = buildRootAnalysisResult(request.difficulty, rootKata, weightsFile, visits);
        nodesCount++;

        Node startNode = root;
        KataAnalysisResult startKata = rootKata;
        if (!startPath.isEmpty()) {
            startNode = addPath(root, startPath);
            startKata = nodeAnalyzer.analyzeNode(brain, startNode, visits, null, overrideSettings);
            startNode.kres = startKata;
        }

        // BFS/DFS queue
        Deque<PrecalcNode> queue = new ArrayDeque<>();
        queue.add(new PrecalcNode(startNode, startKata, startPath, startDepth));

        int playerColor = root.getToMove();

        while (!queue.isEmpty() && nodesCount < maxNodes) {
            PrecalcNode current = queue.poll();
            if (current.depth >= maxDepth) continue;

            // Select policy based on whose turn it is:
            // - Player's turn: use policy (best moves)
            // - Computer's turn: use humanPolicy (human-like moves)
            boolean isPlayerTurn = (current.node.getToMove() == playerColor);
            List<Double> policy = isPlayerTurn
                ? current.kata.policy
                : selectPolicy(current.kata);
            if (policy == null) continue;

            Point lastMove = current.node.findMove();

            for (var pol : current.kata.getTopPolicy(10, policy)) {
                if (nodesCount >= maxNodes) break;
                if (pol.policy < config.precalculationMinPolicy) break;

                // Skip tenuki
                if (lastMove != null && isTenuki(lastMove, new Point(pol.x, pol.y))) continue;

                // Skip moves outside area constraints
                Point candidatePoint = new Point(pol.x, pol.y);
                if (isPlayerTurn && !config.isPlayerMoveAllowed(candidatePoint)) {
                    System.out.println("  Precalc: skipped " + Intersection.toGTPloc(pol.x, pol.y) + " (outside player area)");
                    continue;
                }
                if (!isPlayerTurn && !config.isComputerMoveAllowed(candidatePoint)) {
                    System.out.println("  Precalc: skipped " + Intersection.toGTPloc(pol.x, pol.y) + " (outside computer area)");
                    continue;
                }

                String move = Intersection.toGTPloc(pol.x, pol.y);
                String path = current.path.isEmpty() ? move : current.path + "," + move;

                // Analyze child
                Node childNode = current.node.addBasicMove(pol.x, pol.y);
                KataAnalysisResult childKata = nodeAnalyzer.analyzeNode(brain, childNode, visits, null, overrideSettings);
                childNode.kres = childKata;

                System.out.println("Precalc: " + path + " (depth=" + (current.depth + 1) + 
                    ", " + (isPlayerTurn ? "player" : "computer") +
                    ", policy=" + df.format(pol.policy) + ", queue=" + queue.size() + ", total=" + nodesCount + ")");

                // Build result
                AnalysisResult result = buildAnalysisResult(path, request.difficulty, childNode,
                    childKata, current.kata, root, rootKata, weightsFile, pol.policy, visits);
                result.analysis = gson.toJson(childKata);
                result.extraInfo = debugInfo.toString();
                result.isAnalyzed = true;
                results.add(result);
                nodesCount++;

                if (Boolean.parseBoolean(props.getProperty("scenario.print_debug_info", "true"))) {
                    System.out.println("dbg: " + debugInfo);
                }

                if (results.size() >= config.precalculationBatchSize) {
                    // Always include root result in every batch submission
                    ArrayList<AnalysisResult> batch = new ArrayList<>();
                    batch.add(rootResult);
                    batch.addAll(results);
                    System.out.println("Submitting batch of " + batch.size() + " results (total: " + nodesCount + ")");
                    submitResults(batch.toArray(AnalysisResult[]::new));
                    results.clear();
                }

                if (result.endness < 0) {
                    if (config.precalculationDepthFirst) {
                        queue.addFirst(new PrecalcNode(childNode, childKata, path, current.depth + 1));
                    } else {
                        queue.addLast(new PrecalcNode(childNode, childKata, path, current.depth + 1));
                    }
                }
            }
        }

        ArrayList<AnalysisResult> batch = new ArrayList<>();
        batch.add(rootResult);
        batch.addAll(results);
        System.out.println("Submitting final batch of " + batch.size() + " results (total: " + nodesCount + ")");
        submitResults(batch.toArray(AnalysisResult[]::new));

        System.out.println("Precalculation complete: " + nodesCount + " nodes analyzed");
        return new AnalysisResult[0];  // All results submitted via callback
    }

    private void applyKomiOverride(Node root) {
        if (root == null || config == null) {
            return;
        }

        if (config.hasKomiOverrideInMetadata()) {
            root.setXtraTag("KM", String.valueOf(config.komi));
            System.out.println("Applied komi override from scenario metadata: " + config.komi);
            return;
        }

        String sgfKomi = root.getXtra("KM");
        if (sgfKomi == null || sgfKomi.isBlank()) {
            root.setXtraTag("KM", String.valueOf(config.komi));
            System.out.println("Applied default komi from scenario.properties: " + config.komi);
        }
    }

    private void addResponseResultsHumanRank(KataBrain brain, Node node, Node root, KataAnalysisResult rootKata, AnalysisResult result, ArrayList<AnalysisResult> results, KataAnalysisResult endKata, String humanRank) throws Exception {
        // use katago human-like policy results
        List<KataAnalysisResult.Policy> top = endKata.getTopPolicy(10, endKata.humanPolicy); // gets all, sorted
        int visits = determineVisits();
        var nodeAnalyzer = new NodeAnalyzer(props);
        Point currentMove = node.findMove();

        List<KataAnalysisResult.Policy> validCandidates = new ArrayList<>();

        // Collect all valid candidate moves
        for (var pol : top) {
            String mv = Intersection.toGTPloc(pol.x, pol.y);
            System.out.println("computer response candidate: " + mv + " pol: " + df.format(pol.policy));

            if (pol.policy < config.minHumanPolicy) {
                System.out.println("  too low policy, skipping");
                continue;
            }

            if (currentMove != null && isTenuki(currentMove, new Point(pol.x, pol.y))) {
                System.out.println("  tenuki move, skipping");
                continue;
            }

            // Skip moves outside computer area constraints
            if (!config.isComputerMoveAllowed(new Point(pol.x, pol.y))) {
                System.out.println("  outside computer allowed area, skipping");
                continue;
            }

            validCandidates.add(pol);
        }

        // If no valid candidates due to filters, force add highest policy move
        // Prefer a move inside area constraints; if none, allow any move to avoid empty results
        if (validCandidates.isEmpty()) {
            if (!top.isEmpty()) {
                // First: find best move that satisfies area constraints (ignoring tenuki/policy filters)
                KataAnalysisResult.Policy forced = null;
                for (var candidate : top) {
                    if (config.isComputerMoveAllowed(new Point(candidate.x, candidate.y))) {
                        forced = candidate;
                        break;
                    }
                }

                // If not: use highest policy move even if outside area
                if (forced == null) {
                    forced = top.get(0);
                    String mv = Intersection.toGTPloc(forced.x, forced.y);
                    System.out.println("WARNING: Forcing response outside area constraint: " + mv + " pol: " + df.format(forced.policy));
                    debugInfo.append("Forced response outside area: ").append(mv).append("; ");
                } else {
                    String mv = Intersection.toGTPloc(forced.x, forced.y);
                    System.out.println("Forcing response (no valid candidates, allowing tenuki move): " + mv + " pol: " + df.format(forced.policy));
                }
                validCandidates.add(forced);
            }
        }

        // Select one candidate to analyze based on humanPolicy
        KataAnalysisResult.Policy selectedToAnalyze = selectCandidateByWeight(validCandidates);
        System.out.println("  Selected to analyze: " + Intersection.toGTPloc(selectedToAnalyze.x, selectedToAnalyze.y) +
                         " (policy: " + df.format(selectedToAnalyze.policy) + ")");

        // Analyze selected candidate, add others as unanalyzed
        for (var pol : validCandidates) {
            String mv = Intersection.toGTPloc(pol.x, pol.y);
            String responsePath = result.path + "," + mv;

            if (pol == selectedToAnalyze) {
                // Fully analyze the selected response
                System.out.println("  Analyzing computer response: " + mv);
                KataQuery.OverrideSettings overrideSettings = buildOverrideSettings(humanRank, HumanLikeStyle.HUMAN);
                Node responseNode = node.addBasicMove(pol.x, pol.y);
                KataAnalysisResult responseKata = nodeAnalyzer.analyzeNode(brain, responseNode, visits, null, overrideSettings);
                responseNode.kres = responseKata;

                double weight = pol.policy;
                AnalysisResult responseResult = buildAnalysisResult(responsePath, result.difficulty,
                    responseNode, responseKata, endKata, root, rootKata, result.katagoWeightsFile, weight, visits);
                responseResult.analysis = gson.toJson(responseKata);
                responseResult.extraInfo = debugInfo.toString();
                responseResult.isAnalyzed = true;

                if (Boolean.parseBoolean(props.getProperty("scenario.print_debug_info", "true"))) {
                    System.out.println("dbg: " + debugInfo);
                }

                results.add(responseResult);
            } else {
                // Add remaining candidates as unanalyzed
                System.out.println("  Adding unanalyzed candidate: " + mv + " (weight only)");
                AnalysisResult candidateResult = new AnalysisResult();
                candidateResult.path = responsePath;
                candidateResult.difficulty = result.difficulty;
                candidateResult.weight = pol.policy;
                candidateResult.katagoWeightsFile = result.katagoWeightsFile;
                candidateResult.isAnalyzed = false;
                candidateResult.loss = 0.0;
                candidateResult.score = 0.0;
                candidateResult.urgency = 0.0;
                candidateResult.endness = config.minEndness;
                candidateResult.katagoPlayouts = 0;

                results.add(candidateResult);
            }
        }
    }

    /**
     * Select one candidate from the list based on their policy weights.
     * Uses weighted random selection with proper handling of edge cases.
     *
     * @param candidates List of candidate moves with their policies
     * @return Selected candidate
     */
    private KataAnalysisResult.Policy selectCandidateByWeight(List<KataAnalysisResult.Policy> candidates) {
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Cannot select from empty candidate list");
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        // Calculate total weight
        double totalWeight = 0.0;
        for (var candidate : candidates) {
            totalWeight += candidate.policy;
        }

        // Generate random value in [0, totalWeight)
        double random = Math.random() * totalWeight;

        // Select candidate based on cumulative weight
        double cumulative = 0.0;
        for (var candidate : candidates) {
            cumulative += candidate.policy;
            if (random < cumulative) {
                return candidate;
            }
        }

        // Fallback the first candidate
        return candidates.get(0);
    }

    private void addResponseResultsMaxRank(KataBrain brain, Node node, Node root, KataAnalysisResult rootKata, AnalysisResult result, ArrayList<AnalysisResult> results, KataAnalysisResult endKata, String rank) throws Exception {
        // Use moveInfos to find response moves (post-search, strongest)
        Point currentMove = node.findMove();

        // Collect valid candidates from moveInfos
        double scoreBaseline = endKata.moveInfos.get(0).scoreLead;
        List<MoveInfo> validCandidates = new ArrayList<>();
        for (MoveInfo candidate : endKata.moveInfos) {
            Point candidatePoint = Intersection.gtp2point(candidate.move);
            double scoreDelta = Math.abs(candidate.scoreLead - scoreBaseline);
            boolean isTenukiMove = currentMove != null && isTenuki(currentMove, candidatePoint);

            if (candidate.visits > 5) {
                System.out.println("-- max mode response " + candidate.move + " scoreDelta: " + df.format(scoreDelta)
                    + " visits: " + candidate.visits + (isTenukiMove ? " (tenuki)" : ""));
            }

            if (scoreDelta > config.maxScoreDropMaxMode || isTenukiMove || candidate.visits < config.minResponseVisitsMaxMode) {
                continue;
            }

            // Skip moves outside computer area constraints
            if (!config.isComputerMoveAllowed(candidatePoint)) {
                System.out.println("  outside computer allowed area, skipping: " + candidate.move);
                continue;
            }

            validCandidates.add(candidate);
        }

        // If no valid candidates, end the problem
        if (validCandidates.isEmpty()) {
            if (config.hasComputerAreaConstraints()) {
                System.out.println("All moveInfos moves filtered by area constraints or tenuki, end problem");
                debugInfo.append("No valid max-mode responses (area constraints + tenuki filter); ");
            } else {
                System.out.println("All moveInfos moves are tenuki moves, end problem");
            }
            result.endness = config.maxEndness;
            return;
        }

        int visits = determineVisits();
        var nodeAnalyzer = new NodeAnalyzer(props);

        // Use HUMAN style for analysis
        KataQuery.OverrideSettings overrideSettings = buildOverrideSettings(rank, HumanLikeStyle.HUMAN);
        // It is necessary to set ignorePreRootHistory to true here to avoid bias from move order in response analysis for ai rank
        overrideSettings.ignorePreRootHistory = true;

        // Analyze the first (best) candidate, add others as unanalyzed
        for (int i = 0; i < validCandidates.size(); i++) {
            MoveInfo move = validCandidates.get(i);
            String responsePath = result.path + "," + move.move;

            if (i == 0) {
                // Fully analyze the best response
                System.out.println("  Analyzing computer response (max level): " + move.move);
                Point movePoint = Intersection.gtp2point(move.move);
                Node responseNode = node.addBasicMove(movePoint.x, movePoint.y);
                KataAnalysisResult responseKata = nodeAnalyzer.analyzeNode(brain, responseNode, visits, null, overrideSettings);
                responseNode.kres = responseKata;

                AnalysisResult responseResult = buildAnalysisResult(responsePath, result.difficulty,
                    responseNode, responseKata, endKata, root, rootKata, result.katagoWeightsFile, (double) move.visits, visits);
                responseResult.analysis = gson.toJson(responseKata);
                responseResult.extraInfo = debugInfo.toString();
                responseResult.isAnalyzed = true;

                if (Boolean.parseBoolean(props.getProperty("scenario.print_debug_info", "true"))) {
                    System.out.println("dbg: " + debugInfo);
                }

                results.add(responseResult);
            } else {
                // Add remaining candidates as unanalyzed
                System.out.println("  Adding unanalyzed candidate (max level): " + move.move + " (visits: " + move.visits + ")");
                AnalysisResult candidateResult = new AnalysisResult();
                candidateResult.path = responsePath;
                candidateResult.difficulty = result.difficulty;
                candidateResult.weight = (double) move.visits;
                candidateResult.katagoWeightsFile = result.katagoWeightsFile;
                candidateResult.isAnalyzed = false;
                candidateResult.loss = 0.0;
                candidateResult.score = 0.0;
                candidateResult.urgency = 0.0;
                candidateResult.endness = config.minEndness;
                candidateResult.katagoPlayouts = 0;

                results.add(candidateResult);
            }
        }
    }

    /**
     * Add optimal moves from the parent node.
     * These are the best moves KataGo recommends at the position before the last move in the path.
     * Includes tenuki moves as well. Each move is fully analyzed with KataGo.
     *
     * @param momNode Parent node (position before the last move)
     * @param momKata KataGo analysis of the parent node
     * @param root Root node for endness calculation
     * @param rootKata KataGo analysis of root node
     * @param path The full path string
     * @param rank The difficulty rank
     * @param weightsFile KataGo weights file name
     * @param results List to add results to
     */
    private void addOptimalMoves(Node momNode, KataAnalysisResult momKata, Node root,
                                 KataAnalysisResult rootKata, String path, String rank,
                                 String weightsFile, ArrayList<AnalysisResult> results) throws Exception {
        if (momKata == null || momKata.moveInfos == null || momKata.moveInfos.isEmpty()) {
            return;
        }

        int movesToAdd = Math.min(momKata.moveInfos.size(), config.maxOptimalMoves);

        // Get the parent path (path without the last move)
        String parentPath = getParentPath(path);

        int visits = determineVisits();
        var nodeAnalyzer = new NodeAnalyzer(props);
        String humanRank = normalizeRank(rank);

        for (int i = 0; i < movesToAdd; i++) {
            MoveInfo optimalMove = momKata.moveInfos.get(i);
            int optimalMoveVisits = optimalMove.visits;

            // Build the path for this optimal move
            String optimalPath = parentPath.isEmpty() ? optimalMove.move : parentPath + "," + optimalMove.move;

            // Skip if this optimal move is the same as the user's submitted path
            if (optimalPath.equals(path)) {
                System.out.println("Skipping optimal move " + optimalMove.move + " - same as player path");
                continue;
            }

            // Create the node for this optimal move and analyze it
            KataQuery.OverrideSettings overrideSettings = buildOverrideSettings(humanRank, HumanLikeStyle.HUMAN);
            Point movePoint = Intersection.gtp2point(optimalMove.move);
            Node optimalNode = momNode.addBasicMove(movePoint.x, movePoint.y);
            KataAnalysisResult optimalKata = nodeAnalyzer.analyzeNode(brain, optimalNode, visits, null, overrideSettings);
            optimalNode.kres = optimalKata;

            AnalysisResult optimalResult = buildAnalysisResult(optimalPath, rank, optimalNode,
                optimalKata, momKata, root, rootKata, weightsFile, (double) optimalMoveVisits, visits);
            optimalResult.analysis = gson.toJson(optimalKata);
            optimalResult.extraInfo = debugInfo.toString();
            optimalResult.isAnalyzed = true;

            if (Boolean.parseBoolean(props.getProperty("scenario.print_debug_info", "true"))) {
                System.out.println("dbg: " + debugInfo);
            }

            results.add(optimalResult);
        }
    }

    /**
     * Get the parent path by removing the last move from the path.
     *
     * @param path The full path string (e.g., "C4,D19,E4")
     * @return The parent path (e.g., "C4,D19"), or empty string if path has only one move
     */
    private String getParentPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int lastComma = path.lastIndexOf(',');
        if (lastComma < 0) {
            return "";  // Only one move in path
        }
        return path.substring(0, lastComma);
    }

    private int determineVisits() {
        String primary = props.getProperty(VISITS_PROPERTY);
        if (primary != null) {
            return Integer.parseInt(primary);
        }
        String fallback = props.getProperty(FALLBACK_VISITS_PROPERTY);
        if (fallback != null) {
            return Integer.parseInt(fallback);
        }
        return 1000;
    }

    /**
     * Determine visits, allowing per-request override from metadata.
     */
    private int determineVisits(AnalysisRequest request) {
        // Check metadata configOverrides for scenario.analysis.visits
        if (request != null && request.scenario != null && request.scenario.metadata != null
                && request.scenario.metadata.configOverrides != null) {
            Object visitsOverride = request.scenario.metadata.configOverrides.get("scenario.analysis.visits");
            if (visitsOverride != null) {
                int v = (visitsOverride instanceof Number)
                        ? ((Number) visitsOverride).intValue()
                        : Integer.parseInt(visitsOverride.toString());
                System.out.println("Using overridden visits from metadata: " + v);
                return v;
            }
        }
        return determineVisits();
    }

    private String normalizeRank(String rank) {
        if (rank == null || rank.isBlank()) {
            return DEFAULT_HUMAN_RANK;
        }
        if (rank.equalsIgnoreCase("pro")) {
            return "9d";
        }
        return rank;
    }

    private List<Double> selectPolicy(KataAnalysisResult kres) {
        return kres.humanPolicy != null ? kres.humanPolicy : kres.policy;
    }

    private AnalysisResult buildRootAnalysisResult(String difficulty, KataAnalysisResult rootKata, String katagoWeightsFile, int katagoPlayouts) {
        AnalysisResult result = new AnalysisResult();
        result.path = "";
        result.difficulty = difficulty;
        result.score = rootKata.blackScore();
        result.loss = 0.0;
        result.urgency = 0.0;
        result.endness = config.minEndness;
        result.katagoPlayouts = katagoPlayouts;
        result.katagoWeightsFile = katagoWeightsFile;
        result.weight = 0.0;
        result.analysis = gson.toJson(rootKata);
        result.extraInfo = "";
        return result;
    }

    private AnalysisResult buildAnalysisResult(String path, String difficulty, Node node,
                                               KataAnalysisResult nodeKata, KataAnalysisResult parentKata,
                                               Node root, KataAnalysisResult rootKata,
                                               String katagoWeightsFile, double weight, int katagoPlayouts) {
        AnalysisResult result = new AnalysisResult();
        result.path = path;
        result.difficulty = difficulty;
        result.score = nodeKata.blackScore();
        result.loss = nodeKata.blackScore() - parentKata.blackScore();
        result.urgency = calculateUrgency(node);
        result.katagoPlayouts = katagoPlayouts;
        result.katagoWeightsFile = katagoWeightsFile;
        result.weight = weight;

        debugInfo.setLength(0);
        result.endness = calculateEndness(result, node, root, rootKata, difficulty);
        debugInfo.append(" endness: ").append(df.format(result.endness)).append("; ");

        return result;
    }

    // adds moves from path to the end of node
    private Node addPath(Node node, String path) throws Exception {
        // path is a comma separated list of moves like "C4,D19,E4"
        String[] moves = path.split(",");
        Point lastMove = null;
        for (String move : moves) {
            Point p = Intersection.gtp2point(move);
            node = node.addBasicMove(p.x, p.y);
            lastMove = p;
        }

        System.out.println("board after moves: "); // draws the board out
        System.out.println(node.board.toString(lastMove)); // draws the board out

        return node;
    }

    /**
     * Validate endness value according to success/failure rules.
     * Success (endness > 0) is only allowed on human move.
     * Failure (endness > 0) is only allowed on computer move.
     *
     * @param endness The calculated endness value
     * @param isHumanMove Whether current move is human's move
     * @param scoreDelta The score change from human's perspective (positive = human gained)
     * @return Validated endness value
     */
    private double validateEndness(double endness, boolean isHumanMove, double scoreDelta) {
        if (endness <= 0) {
            return endness;
        }
        boolean isSuccess = scoreDelta >= -0.1;
        if (isSuccess && isHumanMove) {
            return endness;
        }
        if (!isSuccess && !isHumanMove) {
            return endness;
        }
        debugInfo.append(String.format("Endness blocked: %s on %s move (success only on human move, failure only on computer move);",
            isSuccess ? "success" : "failure", isHumanMove ? "human" : "computer"));
        return config.minEndness;
    }

    /**
     * Calculate endness value to determine if the problem should end.
     * Considers multiple factors according to the spec.
     *
     * @param result Analysis result object
     * @param node Current node
     * @param root Root node
     * @param rootKata KataGo analysis of root node
     * @param difficulty The difficulty level (e.g., "max", "10k", "5d")
     * @return endness value: > 0 means should end, <= 0 means continue
     */
    private double calculateEndness(AnalysisResult result, Node node, Node root,
                                   KataAnalysisResult rootKata, String difficulty) {
        // Determine if this is a human move or computer move
        boolean isHumanMove = (node.getToMove() != root.getToMove());
        debugInfo.append("human: ").append(isHumanMove).append("; ");
        double scoreDeltaBp = result.score - rootKata.blackScore(); // From black's perspective
        // scoreDelta from human's perspective (positive = human gained advantage)
        double scoreDelta = (root.getToMove() == Intersection.BLACK) ? scoreDeltaBp : -scoreDeltaBp;
        debugInfo.append("score delta: ").append(df.format(scoreDelta)).append("; ");

        // Calculate urgency to determine if position is important enough to continue
        double urgency = calculateUrgency(node);
        debugInfo.append("urgency: ").append(df.format(urgency)).append("; ");

        double avgOwnership = calculateHumanMovesOwnership(node, root);
        debugInfo.append("avgOwnership: ").append(df.format(avgOwnership)).append("; ");

        // if too few moves, don't end no matter the state
        int minMovesToEnd = config.minMoves;
        if (node.depth < minMovesToEnd) {
            debugInfo.append("cannot end before moves: ").append(node.depth).append("; ");
            return -1;
        }

        if (urgency < config.minUrgencyToContinue) {
//            // Even a move with high urgency, still need to end if the game has already lost too much
//            final double MAX_LOSING_SCORE_AFTER_TENUKI = 5.0;
//            if (scoreDelta < - (urgency + MAX_LOSING_SCORE_AFTER_TENUKI)) {
//                debugInfo.append(String.format("Endness: high urgency (%.2f) but game has already lost %.1f, ending;",
//                    urgency, -scoreDelta));
//                return validateEndness(config.maxEndness, isHumanMove, scoreDelta);
//            }
            debugInfo.append(String.format("Endness: low urgency (%.2f); ", urgency));
            return 1.0 + (urgency > 0.1 ? 1 / urgency : 5);
        }

        // Significant score change
        if (Math.abs(scoreDelta) >= config.scoreDropThreshold) {
            // Check ownership of human moves in path to determine if stones are clearly owned by opponent
            if (scoreDelta < -config.scoreDropThreshold) {
                if (!Double.isNaN(avgOwnership)) {
                    int playerColor = root.getToMove();
                    boolean opponentOwned;
                    boolean clearlyDead;
                    if (playerColor == Intersection.BLACK) {
                        opponentOwned = avgOwnership < 0;
                        clearlyDead = avgOwnership < -config.ownershipThreshold;
                    } else {
                        opponentOwned = avgOwnership > 0;
                        clearlyDead = avgOwnership > config.ownershipThreshold;
                    }

                    // Ownership endness logic:
                    // - Stones clearly live: allow ending
                    // - Stones belong to opponent but not clearly dead: continue
                    // - Stones clearly dead: allow ending
                    if (opponentOwned && !clearlyDead) {
                        debugInfo.append(String.format("Significant score change (%.1f), but ownership unclear, continuing;", scoreDelta));
                    } else {
                        if ((isHumanMove && scoreDelta > 0) || (!isHumanMove && scoreDelta < 0)) {
                            debugInfo.append(String.format("Endness: significant score change (%.1f);", scoreDelta));
                            return validateEndness(config.maxEndness, isHumanMove, scoreDelta);
                        }
                        debugInfo.append(String.format("Significant score change (%.1f), continuing;", scoreDelta));
                    }
                } else {
                    debugInfo.append(String.format("Significant score change (%.1f), but ownership unclear, continuing;", scoreDelta));
                }
            } else {
                if ((isHumanMove && scoreDelta > 0) || (!isHumanMove && scoreDelta < 0)) {
                    debugInfo.append(String.format("Endness: significant score change (%.1f);", scoreDelta));
                    return validateEndness(config.maxEndness, isHumanMove, scoreDelta);
                }
                debugInfo.append(String.format("Significant score change (%.1f), continuing;", scoreDelta));
            }
        }

        double endness = config.minEndness;

        // Value of a tenuki - check if computer wants to tenuki
        // Only check on player's move
        // For "max" difficulty, use moveInfos
        // For other difficulties, use policy
        boolean wantsTenukiResult = difficulty.equals("max")
            ? wantsTenukiByMoveInfos(node)
            : wantsTenuki(node);
        if (isHumanMove && wantsTenukiResult) {
            if (node.depth <= config.minDepthForEndness) {
                debugInfo.append("Endness: computer wants tenuki but depth too low, continue;");
                return config.minEndness;
            }
            debugInfo.append("Endness: computer wants to tenuki;");
            return validateEndness(config.maxEndness, isHumanMove, scoreDelta);
        }

        // Depth of tree - deeper means more likely to end (gentle acceleration)
        int depthBeyondMin = Math.max(0, node.depth - config.minDepthForEndness);
        double depthRatio = depthBeyondMin / config.depthTargetMoves;
        double depthFactor = Math.pow(depthRatio, config.depthPower) + DEPTH_FACTOR_OFFSET;
        endness += depthFactor;
        debugInfo.append(String.format("DepthFactor: %.2f;", depthFactor));

        // Only check on computer move, to see if player still has sente moves to play
        if (!isHumanMove && !hasSenteMoves(node)) {
            if (node.depth <= config.minDepthForEndness) {
                debugInfo.append("Endness: no sente but depth too low, continue;");
                return config.minEndness;
            }
            debugInfo.append("Endness: no sente;");
            return validateEndness(config.maxEndness, isHumanMove, scoreDelta);
        }

        // TODO: Total loss - maybe change to continuous value instead of threshold

        return validateEndness(endness, isHumanMove, scoreDelta);
    }

    /**
     * Calculate average ownership of human moves in the path.
     * Ownership: +1 (black owns) to -1 (white owns)
     * This is used to determine if stones added by human moves are clearly dead.
     *
     * @param node Current node (end of path)
     * @param root Root node (start of path)
     * @return Average ownership value
     */
    private double calculateHumanMovesOwnership(Node node, Node root) {
        if (node.kres == null || node.kres.ownership == null) {
            debugInfo.append("No ownership data, assuming unclear;");
            return Double.NaN;
        }

        List<Point> humanMovePositions = new ArrayList<>();
        Node current = node;
        int playerColor = root.getToMove();

        while (current != null && current != root) {
            boolean isHumanMove = (current.getToMove() != playerColor);
            if (isHumanMove) {
                Point move = current.findMove();
                if (move != null && move.x >= 0 && move.x < 19 && move.y >= 0 && move.y < 19) {
                    humanMovePositions.add(move);
                }
            }
            current = current.mom;
        }

        if (humanMovePositions.isEmpty()) {
            debugInfo.append("No human moves found;");
            return Double.NaN;
        }

        double totalOwnership = 0.0;
        int validPositions = 0;

        for (Point pos : humanMovePositions) {
            int index = pos.x + pos.y * 19;
            if (index >= 0 && index < node.kres.ownership.size()) {
                double ownership = node.kres.ownership.get(index);
                totalOwnership += ownership;
                validPositions++;
            }
        }

        if (validPositions == 0) {
            debugInfo.append("No valid ownership positions;");
            return Double.NaN;
        }

        double avgOwnership = totalOwnership / validPositions;

        String playerColorStr = (playerColor == Intersection.BLACK) ? "B" : "W";
        debugInfo.append(String.format("Ownership: %d moves (player=%s), avg=%.2f;",
            validPositions, playerColorStr, avgOwnership));

        return avgOwnership;
    }

    /**
     * Calculate urgency value to determine how important it is to keep playing in this position.
     * Urgency is defined as the value of the best move vs pass.
     * High urgency indicates the problem hasn't been resolved and we should keep playing it out.
     *
     * @param node Current node with KataGo analysis (node.kres must be set)
     * @return urgency value: higher means more urgent to continue
     */
    private double calculateUrgency(Node node) {
        if (node.kres == null || node.kres.rootInfo == null ||
            node.kres.moveInfos == null || node.kres.moveInfos.isEmpty()) {
            return 0.0;
        }

        // Get the best move
        try {
            List<MoveInfo> moves = node.kres.moveInfos;
            Point currentMove = node.findMove();
            List<Point> recentMoves = (currentMove != null) ? getRecentMoves(node, config.tenukiHistoryMoves) : new ArrayList<>();

            // Find the best non-tenuki move
            MoveInfo bestMove = null;
            for (MoveInfo moveInfo : moves) {
                Point candidatePoint = Intersection.gtp2point(moveInfo.move);

                if (!recentMoves.isEmpty() && isTenukiFromRecent(candidatePoint, recentMoves)) {
                    continue;
                }

                // Skip moves outside area constraints (check both sides since urgency is positional)
                if (config.hasPlayerAreaConstraints() && !config.isPlayerMoveAllowed(candidatePoint)) {
                    continue;
                }

                bestMove = moveInfo;
                break;
            }

            if (bestMove == null) {
                if (config.hasPlayerAreaConstraints()) {
                    debugInfo.append("Urgency: 0.00 (all candidate moves are tenuki or outside area);");
                } else {
                    debugInfo.append("Urgency: 0.00 (all candidate moves are tenuki);");
                }
                return 0.0;
            }

            double bestScore = bestMove.scoreLead;

            // Analyze pass move (19, 19 is pass in the game tree)
            Node passNode = node.addBasicMove(19, 19);
            NodeAnalyzer nodeAnalyzer = new NodeAnalyzer(props, false);
            KataAnalysisResult passKata = nodeAnalyzer.analyzeNode(brain, passNode, config.passMoveVisits,
                (ArrayList<String>) null, (KataQuery.OverrideSettings) null);
            double passScore = passKata.rootInfo.scoreLead;
            double urgency = Math.abs(bestScore - passScore);

            debugInfo.append(String.format("Urgency: %.2f (best non-tenuki=%s sc=%.1f, pass sc=%.1f);",
                urgency, bestMove.move, bestScore, passScore));

            return urgency;
        } catch (Exception e) {
            debugInfo.append("Error calculating urgency");
            return 0.0;
        }
    }

    /**
     * Check if KataGo's best move is tenuki.
     * Uses moveInfos[0] which is more accurate than raw policy after search.
     * Used for "max" difficulty level.
     *
     * @param node Current node
     * @return true if best move is tenuki, false otherwise
     */
    private boolean wantsTenukiByMoveInfos(Node node) {
        Point currentMove = node.findMove();
        if (currentMove == null || node.kres == null) {
            return false;
        }

        // Check for ko situation first - ko threats should not count as tenuki
        if (isKoSituation(node)) {
            debugInfo.append("Ko situation (moveInfos), ignore tenuki;");
            return false;
        }

        if (node.kres.moveInfos == null || node.kres.moveInfos.isEmpty()) {
            return false;
        }

        List<Point> recentMoves = getRecentMoves(node, config.tenukiHistoryMoves);

        MoveInfo bestMove = node.kres.moveInfos.get(0);
        Point bestMovePoint = Intersection.gtp2point(bestMove.move);

        if (isTenukiFromRecent(bestMovePoint, recentMoves)) {
            debugInfo.append(String.format("Wants Tenuki(moveInfos): best=%s visits=%d;",
                bestMove.move, bestMove.visits));
            return true;
        }

        debugInfo.append(String.format("Best moveInfos move %s (visits=%d) is not tenuki;",
            bestMove.move, bestMove.visits));
        return false;
    }

    /**
     * Check if KataGo/human policy wants to tenuki
     * Uses humanPolicy if available, otherwise falls back to moveInfos.
     * Checks against the last N moves to determine if a move is tenuki.
     * Conditions:
     * 1. Best move (by humanPolicy) is tenuki
     * 2. All high policy moves are tenuki
     * 3. Not a ko situation (ko threats don't count as tenuki)
     * @param node Current node
     * @return true if wants to tenuki, false otherwise
     */
    private boolean wantsTenuki(Node node) {
        Point currentMove = node.findMove();
        if (currentMove == null || node.kres == null) {
            return false;
        }

        // Check for ko situation first - ko threats should not count as tenuki
        if (isKoSituation(node)) {
            debugInfo.append("Ko situation, ignore tenuki;");
            return false;
        }

        // Get recent moves for tenuki checking
        List<Point> recentMoves = getRecentMoves(node, config.tenukiHistoryMoves);

        // Use humanPolicy if available, otherwise fall back to regular policy
        List<Double> policy = selectPolicy(node.kres);
        // if (policy == null) {
        //     // Fall back to moveInfos-based logic
        //     return wantsTenukiByMoveInfos(node, recentMoves);
        // }
        // Get top policy moves sorted by humanPolicy
        List<KataAnalysisResult.Policy> topMoves = node.kres.getTopPolicy(10, policy);
        if (topMoves.isEmpty()) {
            return false;
        }

        // Check the best move by policy
        KataAnalysisResult.Policy bestMove = topMoves.get(0);
        Point bestMovePoint = new Point(bestMove.x, bestMove.y);
        String bestMoveStr = Intersection.toGTPloc(bestMove.x, bestMove.y);

        // Best move must be tenuki (far from all recent moves)
        if (!isTenukiFromRecent(bestMovePoint, recentMoves)) {
            debugInfo.append(String.format("Best humanPolicy move %s (policy=%.4f) is not tenuki;",
                    bestMoveStr, bestMove.policy));
            return false;
        }

        // Check all high policy moves to see if any non-tenuki exists
        List<String> tenukiMoves = new ArrayList<>();
        List<String> nonTenukiMoves = new ArrayList<>();

        // Determine if we're using humanPolicy or regular policy
        boolean usingHumanPolicy = node.kres.humanPolicy != null;
        String policyLabel = usingHumanPolicy ? "hp" : "p";

        // bestMove.policy already contains the value from policyToUse (humanPolicy or regular policy)
        tenukiMoves.add(String.format("%s(%s=%.2f)", bestMoveStr, policyLabel, bestMove.policy));

        for (int i = 1; i < topMoves.size(); i++) {
            KataAnalysisResult.Policy candidate = topMoves.get(i);

            // Stop if policy is too low
            if (candidate.policy < config.minHumanPolicy) {
                break;
            }

            Point candidatePoint = new Point(candidate.x, candidate.y);
            String candidateStr = Intersection.toGTPloc(candidate.x, candidate.y);

            // candidate.policy already contains the value from policyToUse
            String candidateWithPolicy = String.format("%s(%s=%.2f)", candidateStr, policyLabel, candidate.policy);

            if (isTenukiFromRecent(candidatePoint, recentMoves)) {
                tenukiMoves.add(candidateWithPolicy);
            } else {
                nonTenukiMoves.add(candidateWithPolicy);
            }
        }        // If any high policy non-tenuki move exists, don't consider it as wanting tenuki
        if (!nonTenukiMoves.isEmpty()) {
            debugInfo.append(String.format("Wants tenuki(%s) but non-tenuki moves %s have high policy;",
                String.join(",", tenukiMoves), nonTenukiMoves));
            return false;
        }

        // All high policy moves are tenuki
        debugInfo.append(String.format("Wants Tenuki(humanPolicy), all high policy moves are tenuki: %s;",
            String.join(",", tenukiMoves)));
        return true;
    }

    /**
     * Get the last N moves from the game tree.
     *
     * @param node Current node
     * @param count Number of recent moves to retrieve
     * @return List of recent move points (most recent first)
     */
    private List<Point> getRecentMoves(Node node, int count) {
        List<Point> recentMoves = new ArrayList<>();
        Node current = node;

        while (current != null && recentMoves.size() < count) {
            Point move = current.findMove();
            if (move != null) {
                recentMoves.add(move);
            }
            current = current.mom;
        }

        return recentMoves;
    }

    /**
     * Check if a move is tenuki from all recent moves.
     * A move is considered tenuki if it's far from all recent moves.
     *
     * @param candidateMove The move to check
     * @param recentMoves List of recent moves
     * @return true if the move is far from all recent moves
     */
    private boolean isTenukiFromRecent(Point candidateMove, List<Point> recentMoves) {
        if (recentMoves.isEmpty()) {
            return false;
        }

        for (Point recentMove : recentMoves) {
            if (!isTenuki(recentMove, candidateMove)) {
                return false;  // Close to at least one recent move
            }
        }

        return true;  // Far from all recent moves
    }

    // /**
    //  * Fallback method to check tenuki using moveInfos when humanPolicy is not available.
    //  */
    // private boolean wantsTenukiByMoveInfos(Node node, List<Point> recentMoves) {
    //     final double SIGNIFICANT_SCORE_LEAD = 2.0;

    //     if (node.kres.moveInfos == null || node.kres.moveInfos.isEmpty()) {
    //         return false;
    //     }

    //     List<MoveInfo> moves = node.kres.moveInfos;
    //     MoveInfo nextBestMove = moves.get(0);

    //     // Check distance of best move from recent moves
    //     Point nextBestMovePoint = Intersection.gtp2point(nextBestMove.move);

    //     // best move must be far away from all recent moves
    //     if (!isTenukiFromRecent(nextBestMovePoint, recentMoves)) {
    //         return false;
    //     }

    //     // If only one candidate move
    //     if (moves.size() < 2) {
    //         debugInfo.append(String.format("Wants Tenuki(%s,only);", nextBestMove.move));
    //         return true;
    //     }

    //     // Check all candidate moves to see if any competitive non-tenuki move exists
    //     final int MAX_MOVES_TO_CHECK = 5;
    //     int movesToCheck = Math.min(moves.size(), MAX_MOVES_TO_CHECK);

    //     List<String> tenukiMoves = new ArrayList<>();
    //     List<String> nonTenukiMoves = new ArrayList<>();
    //     tenukiMoves.add(nextBestMove.move);

    //     for (int i = 1; i < movesToCheck; i++) {
    //         MoveInfo candidate = moves.get(i);

    //         // Check if this move is competitive with the best move (by score)
    //         double scoreDiff = nextBestMove.scoreLead - candidate.scoreLead;
    //         if (scoreDiff >= SIGNIFICANT_SCORE_LEAD) {
    //             break;  // This and remaining moves are not competitive
    //         }

    //         // This move is competitive, check if it's tenuki or not
    //         Point candidatePoint = Intersection.gtp2point(candidate.move);
    //         if (isTenukiFromRecent(candidatePoint, recentMoves)) {
    //             tenukiMoves.add(candidate.move);
    //         } else {
    //             nonTenukiMoves.add(candidate.move);
    //         }
    //     }

    //     // If any competitive non-tenuki move exists, don't consider it as wanting tenuki
    //     if (!nonTenukiMoves.isEmpty()) {
    //         debugInfo.append(String.format("Wants tenuki(%s) but non-tenuki moves %s are competitive;",
    //             String.join(",", tenukiMoves), nonTenukiMoves));
    //         return false;
    //     }

    //     // All competitive moves are tenuki
    //     debugInfo.append(String.format("Wants Tenuki(moveInfos), all competitive moves are tenuki: %s;",
    //         String.join(",", tenukiMoves)));
    //     return true;
    // }

    /**
     * Check if the distance between two points constitutes a tenuki.
     *
     * @param from Starting point
     * @param to Destination point
     * @return true if the distance is >= tenukiDistanceThreshold
     */
    private boolean isTenuki(Point from, Point to) {
        double distance = Math.sqrt(
            Math.pow(to.x - from.x, 2) +
            Math.pow(to.y - from.y, 2)
        );
        return distance >= config.tenukiDistanceThreshold;
    }

    /**
     * Check if there are any sente moves remaining in the current position.
     * A move is considered sente if the opponent must respond locally (not tenuki).
     * Uses humanPolicy for ordering if available.
     *
     * Algorithm:
     * 1. Get top moves sorted by humanPolicy (or regular policy as fallback)
     * 2. For each non-tenuki candidate, find its PV in moveInfos
     * 3. If opponent's response in PV is not tenuki, it's a sente move
     * 4. Return true if at least one sente move exists
     *
     * @param node Current node
     * @return true if there are sente moves, false if all moves allow tenuki
     */
    private boolean hasSenteMoves(Node node) {
        if (node.kres == null) {
            return false;
        }

        Point currentMove = node.findMove();
        if (currentMove == null) {
            return false;
        }

        if (node.kres.moveInfos == null || node.kres.moveInfos.isEmpty()) {
            return false;
        }

        // Check for ko situation first - ko threats should not count as tenuki
        if (isKoSituation(node)) {
            debugInfo.append("Ko situation in sente check, ignore tenuki for ko threats;");
            // In ko situation, consider all non-low-policy moves as sente (local responses)
            int senteCount = 0;
            for (MoveInfo moveInfo : node.kres.moveInfos) {
                if (moveInfo.prior >= config.minSentePolicy) {
                    senteCount++;
                    if (senteCount > 0) {
                        debugInfo.append("HasSente(ko);");
                        return true;
                    }
                }
            }
            debugInfo.append("NoSente(ko,lowPolicy);");
            return false;
        }

        // Use moveInfos directly (already searched by KataGo, guaranteed to have PV)
        // And sente checking is objective so not using humanPolicy here
        List<MoveInfo> moveInfos = node.kres.moveInfos;

        int senteCount = 0;
        int checkedCount = 0;
        List<String> senteMoves = new ArrayList<>();
        List<String> goteMoves = new ArrayList<>();
        List<String> lowPolicyMoves = new ArrayList<>();
        List<String> tenukiMoves = new ArrayList<>();

        for (MoveInfo moveInfo : moveInfos) {
            if (checkedCount >= config.maxSenteCandidates) {
                break;
            }

            checkedCount++;

            double prior = moveInfo.prior;

            // Skip if policy is too low
            if (prior < config.minSentePolicy) {
                lowPolicyMoves.add(String.format("%s(p=%.2f)", moveInfo.move, prior));
                continue;
            }

            Point candidatePoint = Intersection.gtp2point(moveInfo.move);

            // Skip if the candidate itself is a tenuki
            if (isTenuki(currentMove, candidatePoint)) {
                tenukiMoves.add(String.format("%s(p=%.2f)", moveInfo.move, prior));
                continue;
            }

            // Skip moves outside the player's allowed area — treated like tenuki for sente checking
            if (config.hasPlayerAreaConstraints() && !config.isPlayerMoveAllowed(candidatePoint)) {
                tenukiMoves.add(String.format("%s(outside-allowed-area)", moveInfo.move));
                continue;
            }

            if (moveInfo.pv == null || moveInfo.pv.size() < 2) {
                goteMoves.add(String.format("%s(p=%.2f)->?", moveInfo.move, prior));
                continue;  // No PV info for this move
            }

            // Check opponent's response using PV (principal variation)
            // pv[0] is our move, pv[1] is opponent's response
            String opponentResponseMove = moveInfo.pv.get(1);
            Point opponentResponse = Intersection.gtp2point(opponentResponseMove);

            // If opponent's response is not tenuki, this is a sente move
            if (!isTenuki(candidatePoint, opponentResponse)) {
                senteCount++;
                senteMoves.add(String.format("%s(p=%.2f)->%s", moveInfo.move, prior, opponentResponseMove));
            } else {
                goteMoves.add(String.format("%s(p=%.2f)->%s", moveInfo.move, prior, opponentResponseMove));
            }
        }

        StringBuilder movesInfo = new StringBuilder();
        if (!senteMoves.isEmpty()) {
            movesInfo.append("sente:").append(String.join(",", senteMoves));
        }
        if (!goteMoves.isEmpty()) {
            if (movesInfo.length() > 0) movesInfo.append(" ");
            movesInfo.append("gote:").append(String.join(",", goteMoves));
        }
        if (!tenukiMoves.isEmpty()) {
            if (movesInfo.length() > 0) movesInfo.append(" ");
            movesInfo.append("tenuki:").append(String.join(",", tenukiMoves));
        }
        if (!lowPolicyMoves.isEmpty()) {
            if (movesInfo.length() > 0) movesInfo.append(" ");
            movesInfo.append("lowP:").append(String.join(",", lowPolicyMoves));
        }

        if (senteCount > 0) {
            debugInfo.append(String.format("HasSente(%s);", movesInfo));
        } else {
            debugInfo.append(String.format("NoSente(%s);", movesInfo));
        }

        return senteCount > 0;

        // List<KataAnalysisResult.Policy> topMoves = node.kres.getTopPolicy(10, policy);
        // for (var pol : topMoves) {
        //     if (checkedCount >= maxSenteCandidates) break;
        //     if (pol.policy < minHumanPolicy) continue;
        //     Point candidatePoint = new Point(pol.x, pol.y);
        //     String candidateMove = Intersection.toGTPloc(pol.x, pol.y);
        //     if (isTenuki(currentMove, candidatePoint)) continue;
        //     checkedCount++;
        //     MoveInfo moveInfo = node.kres.getMoveInfo(candidateMove);
        //     if (moveInfo == null || moveInfo.pv == null || moveInfo.pv.size() < 2) {
        //         unknownMoves.add(String.format("%s(hp=%.2f)", candidateMove, pol.policy));
        //         continue;
        //     }
        //     String opponentResponseMove = moveInfo.pv.get(1);
        //     Point opponentResponse = Intersection.gtp2point(opponentResponseMove);
        //     if (!isTenuki(candidatePoint, opponentResponse)) {
        //         senteCount++;
        //         senteMoves.add(String.format("%s(hp=%.2f)->%s", candidateMove, pol.policy, opponentResponseMove));
        //     } else {
        //         goteMoves.add(String.format("%s(hp=%.2f)->%s", candidateMove, pol.policy, opponentResponseMove));
        //     }
        // }
        // --- End old implementation ---
    }


    /**
     * Detect if the current position involves a ko situation.
     * Checks if the current or recent moves (2 moves back, 3 moves total) are related to ko.
     * This handles ko fight sequences: ko capture -> ko threat -> respond to threat.
     *
     * @param node Current node
     * @return true if ko situation detected
     */
    private boolean isKoSituation(Node node) {
        Point currentMove = node.findMove();
        if (currentMove == null) {
            return false;
        }

        // Check if the current move is a ko
        if (node.board.isKo(currentMove)) {
            return true;
        }

        Node current = node.mom;
        int movesBack = 0;
        int maxMovesBack = 2;

        while (current != null && movesBack < maxMovesBack) {
            if (current.board != null) {
                Point prevMove = current.findMove();
                if (prevMove != null && current.board.isKo(prevMove)) {
                    return true;
                }
            }
            current = current.mom;
            movesBack++;
        }

        return false;
    }
}
