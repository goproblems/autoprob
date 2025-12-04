package autoprob.scenario;

import autoprob.KataBrain;
import autoprob.NodeAnalyzer;
import autoprob.api.AnalysisRequest;
import autoprob.api.AnalysisResult;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.parse.Parser;
import autoprob.katastruct.KataAnalysisResult;
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
    private static final DecimalFormat df = new DecimalFormat("0.00");

    private static final String DEFAULT_HUMAN_RANK = "10k";
    private static final String VISITS_PROPERTY = "scenario.analysis.visits";
    private static final String FALLBACK_VISITS_PROPERTY = "search.visits";

    private final Properties props;
    private final KataBrain brain;
    private final Parser parser = new Parser();
    private final StringBuilder debugInfo = new StringBuilder();
    private final Gson gson = new Gson();

    // Configurable parameters loaded from properties
    private final double minHumanPolicy;
    private final int minDepthForEndness;
    private final double scoreDropThreshold;
    private final double maxEndness;
    private final double minEndness;
    private final double depthTargetMoves;
    private final double depthPower;
    private final double maxUrgency;
    private final int tenukiHistoryMoves;
    private final double tenukiDistanceThreshold;
    private final int maxOptimalMoves;
    private final int maxSenteCandidates;
    private final int precalculationMaxDepth;
    private final int precalculationMaxNodes;
    private final int precalculationBatchSize;
    private final boolean precalculationDepthFirst;

    private ResultSubmitter resultSubmitter;

    @FunctionalInterface
    public interface ResultSubmitter {
        void submit(AnalysisResult[] results) throws Exception;
    }

    public Analysis(Properties props, KataBrain brain) throws Exception {
        this.props = Objects.requireNonNull(props, "props");
        this.brain = brain;

        // Load configurable parameters from properties
        this.minHumanPolicy = Double.parseDouble(props.getProperty("scenario.min_response_policy", "0.05"));
        this.minDepthForEndness = Integer.parseInt(props.getProperty("scenario.min_depth_for_endness", "5"));
        this.scoreDropThreshold = Double.parseDouble(props.getProperty("scenario.score_drop_threshold", "15.0"));
        this.maxEndness = Double.parseDouble(props.getProperty("scenario.max_endness", "1.0"));
        this.minEndness = Double.parseDouble(props.getProperty("scenario.min_endness", "-1.0"));
        this.depthTargetMoves = Double.parseDouble(props.getProperty("scenario.depth_target_moves", "30.0"));
        this.depthPower = Double.parseDouble(props.getProperty("scenario.depth_power", "1.1"));
        this.maxUrgency = Double.parseDouble(props.getProperty("scenario.max_urgency", "10.0"));
        this.tenukiHistoryMoves = Integer.parseInt(props.getProperty("scenario.tenuki_history_moves", "3"));
        this.tenukiDistanceThreshold = Double.parseDouble(props.getProperty("scenario.tenuki_distance_threshold", "6.0"));
        this.maxOptimalMoves = Integer.parseInt(props.getProperty("scenario.max_optimal_moves", "1"));
        this.maxSenteCandidates = Integer.parseInt(props.getProperty("scenario.max_sente_candidates", "5"));
        this.precalculationMaxDepth = Integer.parseInt(props.getProperty("scenario.precalculation_max_depth", "20"));
        this.precalculationMaxNodes = Integer.parseInt(props.getProperty("scenario.precalculation_max_nodes", "3000"));
        this.precalculationBatchSize = Integer.parseInt(props.getProperty("scenario.precalculation_batch_size", "10"));
        this.precalculationDepthFirst = props.getProperty("scenario.precalculation_strategy", "bfs").equalsIgnoreCase("dfs");
    }

    public void setResultSubmitter(ResultSubmitter submitter) {
        this.resultSubmitter = submitter;
    }

    private void submitResults(AnalysisResult[] results) throws Exception {
        if (resultSubmitter != null && results.length > 0) {
            resultSubmitter.submit(results);
        }
    }

    /**
     * Runs KataGo on the supplied request path and summarizes the outcome.
     * If depth > 0 and path is empty, runs in precalculation mode to precalculate the game tree.
     * Otherwise, analyzes the given path.
     */
    public AnalysisResult[] analyze(AnalysisRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.scenario, "request.scenario");
        if (request.scenario.sgf == null || request.scenario.sgf.isBlank()) {
            throw new IllegalArgumentException("Scenario SGF is required");
        }

        boolean isPrecalculationMode = request.isPrecalculate != null && request.isPrecalculate;

        if (isPrecalculationMode) {
            return analyzePrecalculation(request);
        }

        return analyzePath(request);
    }

    /**
     * Analyzes a specific path (original behavior).
     */
    private AnalysisResult[] analyzePath(AnalysisRequest request) throws Exception {
        var nodeAnalyzer = new NodeAnalyzer(props);

        Node root = parser.parse(request.scenario.sgf);
        System.out.println(root.board);
        System.out.println("To move: " + (root.getToMove() == Intersection.BLACK ? "black" : "white"));

        int visits = determineVisits();
        System.out.println("Visits: " + visits);

        String humanRank = normalizeRank(request.difficulty);

        // first we analyze the root position, establish a baseline for score and more
        KataAnalysisResult rootKata = nodeAnalyzer.analyzeNode(brain, root, visits, null, humanRank);

        // play the moves in the path, get a new position from that
        Node node = addPath(root, request.path);
        // analyze the parent node, so we know direct loss for the last move
        KataAnalysisResult momKata = nodeAnalyzer.analyzeNode(brain, node.mom, visits, null, humanRank);

        // analyze the end position after the path
        KataAnalysisResult endKata = nodeAnalyzer.analyzeNode(brain, node, visits, null, humanRank);
        node.kres = endKata;

        // get last fragment for model
        String fullModelPath = props.getProperty("kata.model");
        String weightsFile = (fullModelPath.substring(fullModelPath.lastIndexOf('/') + 1)).substring(fullModelPath.lastIndexOf('\\') + 1);

        AnalysisResult result = buildAnalysisResult(request.path, request.difficulty, node,
            endKata, momKata, root, rootKata, weightsFile, 0.0);
        result.analysis = gson.toJson(endKata);
        result.extraInfo = debugInfo.toString();

        // make extendable list of possible results
        ArrayList<AnalysisResult> results = new ArrayList<>();

        // Always add root node analysis for calculating total loss for scenario node
        AnalysisResult rootResult = buildRootAnalysisResult(request.difficulty, rootKata, weightsFile);
        results.add(rootResult);
        results.add(result);

        // Add optimal moves from parent node (momKata) - these are the best moves KataGo recommends at that position
        addOptimalMoves(node.mom, momKata, root, rootKata, request.path, request.difficulty, weightsFile, results);

        // if not an end move, we can add possible response moves from katago
        if (result.endness < 0.0) {
            if (humanRank.equals("ai")) {
                addResponseResults(brain, node, root, rootKata, result, results, endKata, humanRank);
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
        System.out.println(root.board);
        System.out.println("To move: " + (root.getToMove() == Intersection.BLACK ? "black" : "white"));

        String startPath = (request.path != null && !request.path.isBlank()) ? request.path : "";
        int startDepth = startPath.isEmpty() ? 0 : startPath.split(",").length;

        System.out.println("Precalculation mode: strategy=" + (precalculationDepthFirst ? "dfs" : "bfs") +
            ", startPath=" + (startPath.isEmpty() ? "(root)" : startPath) +
            ", maxDepth=" + precalculationMaxDepth + 
            ", maxNodes=" + precalculationMaxNodes + ", batchSize=" + precalculationBatchSize);

        int visits = determineVisits();
        String humanRank = normalizeRank(request.difficulty);
        int maxDepth = precalculationMaxDepth;
        int maxNodes = precalculationMaxNodes;

        String fullModelPath = props.getProperty("kata.model");
        String weightsFile = (fullModelPath.substring(fullModelPath.lastIndexOf('/') + 1))
            .substring(fullModelPath.lastIndexOf('\\') + 1);

        ArrayList<AnalysisResult> results = new ArrayList<>();
        int nodesCount = 0;

        // Analyze root
        KataAnalysisResult rootKata = nodeAnalyzer.analyzeNode(brain, root, visits, null, humanRank);
        root.kres = rootKata;

        AnalysisResult rootResult = buildRootAnalysisResult(request.difficulty, rootKata, weightsFile);
        nodesCount++;

        Node startNode = root;
        KataAnalysisResult startKata = rootKata;
        if (!startPath.isEmpty()) {
            startNode = addPath(root, startPath);
            startKata = nodeAnalyzer.analyzeNode(brain, startNode, visits, null, humanRank);
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
                if (pol.policy < minHumanPolicy) break;

                // Skip tenuki
                if (lastMove != null && isTenuki(lastMove, new Point(pol.x, pol.y))) continue;

                String move = Intersection.toGTPloc(pol.x, pol.y);
                String path = current.path.isEmpty() ? move : current.path + "," + move;

                // Analyze child
                Node childNode = current.node.addBasicMove(pol.x, pol.y);
                KataAnalysisResult childKata = nodeAnalyzer.analyzeNode(brain, childNode, visits, null, humanRank);
                childNode.kres = childKata;

                System.out.println("Precalc: " + path + " (depth=" + (current.depth + 1) + 
                    ", " + (isPlayerTurn ? "player" : "computer") +
                    ", policy=" + df.format(pol.policy) + ", queue=" + queue.size() + ", total=" + nodesCount + ")");

                // Build result
                AnalysisResult result = buildAnalysisResult(path, request.difficulty, childNode,
                    childKata, current.kata, root, rootKata, weightsFile, pol.policy);
                result.analysis = gson.toJson(childKata);
                result.extraInfo = debugInfo.toString();
                results.add(result);
                nodesCount++;

                if (results.size() >= precalculationBatchSize) {
                    // Always include root result in every batch submission
                    ArrayList<AnalysisResult> batch = new ArrayList<>();
                    batch.add(rootResult);
                    batch.addAll(results);
                    System.out.println("Submitting batch of " + batch.size() + " results (total: " + nodesCount + ")");
                    submitResults(batch.toArray(AnalysisResult[]::new));
                    results.clear();
                }

                if (result.endness < 0) {
                    if (precalculationDepthFirst) {
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

    private void addResponseResultsHumanRank(KataBrain brain, Node node, Node root, KataAnalysisResult rootKata, AnalysisResult result, ArrayList<AnalysisResult> results, KataAnalysisResult endKata, String humanRank) throws Exception {
        // use katago human-like policy results
        List<KataAnalysisResult.Policy> top = endKata.getTopPolicy(10, endKata.humanPolicy); // gets all, sorted
        int visits = determineVisits();
        var nodeAnalyzer = new NodeAnalyzer(props);
        int sizeBefore = results.size();
        Point currentMove = node.findMove();

        // run through these in order, if they are high enough policy and in a good location, add to responses
        for (var pol : top) {
            String mv = Intersection.toGTPloc(pol.x, pol.y);
            System.out.println("human response: " + mv + " pol: " + df.format(pol.policy));
            if (pol.policy < minHumanPolicy) {
                System.out.println("  too low policy, skipping");
                continue;
            }

            if (currentMove != null && isTenuki(currentMove, new Point(pol.x, pol.y))) {
                System.out.println("  tenuki move, skipping");
                continue;
            }

            addResponseMove(brain, node, root, rootKata, result, results, endKata, humanRank, pol, visits, nodeAnalyzer);
        }

        // No response added due to policy/tenuki filters, force add the first non-tenuki move
        if (results.size() == sizeBefore) {
            for (var pol : top) {
                if (currentMove != null && isTenuki(currentMove, new Point(pol.x, pol.y))) {
                    continue;
                }
                System.out.println("Forcing response (no valid moves): " + Intersection.toGTPloc(pol.x, pol.y) + " pol: " + df.format(pol.policy));
                addResponseMove(brain, node, root, rootKata, result, results, endKata, humanRank, pol, visits, nodeAnalyzer);
                return;
            }
        }
    }

    private void addResponseMove(KataBrain brain, Node node, Node root, KataAnalysisResult rootKata, AnalysisResult result, ArrayList<AnalysisResult> results, KataAnalysisResult endKata, String humanRank, KataAnalysisResult.Policy pol, int visits, NodeAnalyzer nodeAnalyzer) throws Exception {
        String mv = Intersection.toGTPloc(pol.x, pol.y);
        MoveInfo mi = endKata.getMoveInfo(mv);
        Integer moveVisits = mi != null ? mi.visits : null;

        Node responseNode = node.addBasicMove(pol.x, pol.y);
        KataAnalysisResult responseKata = nodeAnalyzer.analyzeNode(brain, responseNode, visits, null, humanRank);
        responseNode.kres = responseKata;

        double weight = moveVisits != null ? (double) moveVisits : 0.0;
        AnalysisResult responseResult = buildAnalysisResult(result.path + "," + mv, result.rank,
            responseNode, responseKata, endKata, root, rootKata, result.katagoWeightsFile, weight);
        responseResult.analysis = gson.toJson(responseKata);
        responseResult.extraInfo = debugInfo.toString();

        results.add(responseResult);
    }

    private void addResponseResults(KataBrain brain, Node node, Node root, KataAnalysisResult rootKata, AnalysisResult result, ArrayList<AnalysisResult> results, KataAnalysisResult endKata, String rank) throws Exception {
        // for now just use previous kata results to get possible moves
        // TODO: add multiple
        MoveInfo move = endKata.moveInfos.get(0);

        Point currentMove = node.findMove();
        if (currentMove != null) {
            Point movePoint = Intersection.gtp2point(move.move);
            if (isTenuki(currentMove, movePoint)) {
                System.out.println("Best move is tenuki, not adding response");
                return;
            }
        }

        int visits = determineVisits();
        var nodeAnalyzer = new NodeAnalyzer(props);

        Point movePoint = Intersection.gtp2point(move.move);
        Node responseNode = node.addBasicMove(movePoint.x, movePoint.y);
        KataAnalysisResult responseKata = nodeAnalyzer.analyzeNode(brain, responseNode, visits, null, rank);
        responseNode.kres = responseKata;

        AnalysisResult responseResult = buildAnalysisResult(result.path + "," + move.move, result.rank,
            responseNode, responseKata, endKata, root, rootKata, result.katagoWeightsFile, (double) move.visits);
        responseResult.analysis = gson.toJson(responseKata);
        responseResult.extraInfo = debugInfo.toString();

        results.add(responseResult);
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

        int movesToAdd = Math.min(momKata.moveInfos.size(), maxOptimalMoves);

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
            Point movePoint = Intersection.gtp2point(optimalMove.move);
            Node optimalNode = momNode.addBasicMove(movePoint.x, movePoint.y);
            KataAnalysisResult optimalKata = nodeAnalyzer.analyzeNode(brain, optimalNode, visits, null, humanRank);
            optimalNode.kres = optimalKata;

            AnalysisResult optimalResult = buildAnalysisResult(optimalPath, rank, optimalNode,
                optimalKata, momKata, root, rootKata, weightsFile, (double) optimalMoveVisits);
            optimalResult.analysis = gson.toJson(optimalKata);
            optimalResult.extraInfo = debugInfo.toString();

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

    private AnalysisResult buildRootAnalysisResult(String rank, KataAnalysisResult rootKata, String weightsFile) {
        AnalysisResult result = new AnalysisResult();
        result.path = "";
        result.rank = rank;
        result.score = rootKata.blackScore();
        result.loss = 0.0;
        result.urgency = 0.0;
        result.endness = minEndness;
        result.katagoPlayouts = rootKata.rootInfo.visits;
        result.katagoWeightsFile = weightsFile;
        result.weight = 0.0;
        result.analysis = gson.toJson(rootKata);
        result.extraInfo = "";
        return result;
    }

    private AnalysisResult buildAnalysisResult(String path, String rank, Node node,
                                               KataAnalysisResult nodeKata, KataAnalysisResult parentKata,
                                               Node root, KataAnalysisResult rootKata,
                                               String weightsFile, double weight) {
        AnalysisResult result = new AnalysisResult();
        result.path = path;
        result.rank = rank;
        result.score = nodeKata.blackScore();
        result.loss = nodeKata.blackScore() - parentKata.blackScore();
        result.urgency = calculateUrgency(node);
        result.katagoPlayouts = nodeKata.rootInfo.visits;
        result.katagoWeightsFile = weightsFile;
        result.weight = weight;

        debugInfo.setLength(0);
        result.endness = calculateEndness(result, node, root, rootKata);

        return result;
    }

    // adds moves from path to the end of node
    private Node addPath(Node node, String path) throws Exception {
        // path is a comma separated list of moves like "C4,D19,E4"
        String[] moves = path.split(",");
        for (String move : moves) {
            Point p = Intersection.gtp2point(move);
            node = node.addBasicMove(p.x, p.y);
        }
        return node;
    }

    /**
     * Calculate endness value to determine if the problem should end.
     * Considers multiple factors according to the spec.
     *
     * @param result Analysis result object
     * @param node Current node
     * @param root Root node
     * @param rootKata KataGo analysis of root node
     * @return endness value: > 0 means should end, <= 0 means continue
     */
    private double calculateEndness(AnalysisResult result, Node node, Node root,
                                   KataAnalysisResult rootKata) {
        // Success - player move with positive score AND gained advantage from root
        boolean isPlayerMove = (node.getToMove() != root.getToMove());
        double scoreDelta = result.score - rootKata.blackScore(); // From black's perspective
        boolean hasAdvantage = (root.getToMove() == Intersection.BLACK && scoreDelta > 0) ||
                               (root.getToMove() == Intersection.WHITE && scoreDelta < 0);

        if (isPlayerMove &&
            (result.score > 0 && root.getToMove() == Intersection.BLACK ||
                result.score <= 0 && root.getToMove() == Intersection.WHITE) &&
            hasAdvantage) {
            debugInfo.append("Positive score on player move, good move, but don't end problem for now;");
            // return maxEndness;
        }

        // Significant score change
        double scoreLoss = (root.getToMove() == Intersection.BLACK) ? -scoreDelta : scoreDelta;
        if (Math.abs(scoreLoss) >= scoreDropThreshold) {
            debugInfo.append(String.format("Endness: significant score change (%.1f);", scoreLoss));
            return maxEndness;
        }

        double endness = minEndness;

        // Value of a tenuki - check if KataGo wants to tenuki
        // Only check on player's move
        if (isPlayerMove && wantsTenuki(node)) {
            if (node.depth <= minDepthForEndness) {
                debugInfo.append("Endness: computer wants tenuki but depth too low, continue;");
                return minEndness;
            }
            debugInfo.append("Endness: computer wants to tenuki;");
            return maxEndness;
        }

        // Depth of tree - deeper means more likely to end (gentle acceleration)
        int depthBeyondMin = Math.max(0, node.depth - minDepthForEndness);
        double depthRatio = depthBeyondMin / depthTargetMoves;
        double depthFactor = Math.pow(depthRatio, depthPower);
        endness += depthFactor;
        debugInfo.append(String.format("DepthFactor: %.2f;", depthFactor));

        // Only check on computer move, to see if player still has sente moves to play
        if (!isPlayerMove && !hasSenteMoves(node)) {
            if (node.depth <= minDepthForEndness) {
                debugInfo.append("Endness: no sente but depth too low, continue;");
                return minEndness;
            }
            // if (!hasHighPolicyMove(node)) {
            // debugInfo.append("Endness: no sente;");
            // return maxEndness;
            // }
            debugInfo.append("Endness: no sente;");
            return maxEndness;
        }

        // TODO: Stones lost - judge dead stone ratio by ownership
        // TODO: Use urgency?
        // TODO: Total loss - maybe change to continuous value instead of threshold

        return endness;
    }

    /**
     * Calculate urgency value to determine how important it is to keep playing in this position.
     * Urgency is defined as the value of a move vs a tenuki.
     * High urgency indicates the problem hasn't been resolved and we should keep playing it out.
     *
     * @param node Current node with KataGo analysis (node.kres must be set)
     * @return urgency value: higher means more urgent to continue
     */
    private double calculateUrgency(Node node) {
        if (node.kres == null || node.kres.moveInfos == null || node.kres.moveInfos.isEmpty()) {
            return 0.0;
        }

        Point currentMove = node.findMove();
        if (currentMove == null) {
            return 0.0;
        }

        // moves are sorted by quality
        List<MoveInfo> moves = node.kres.moveInfos;

        // Find the best move (not tenuki)
        MoveInfo bestMove = null;
        for (MoveInfo move : moves) {
            Point movePoint = Intersection.gtp2point(move.move);
            if (!isTenuki(currentMove, movePoint)) {
                bestMove = move;
                break;
            }
        }

        // Find the best tenuki move
        MoveInfo bestTenukiMove = null;
        for (MoveInfo move : moves) {
            Point movePoint = Intersection.gtp2point(move.move);
            if (isTenuki(currentMove, movePoint)) {
                bestTenukiMove = move;
                break;
            }
        }

        if (bestMove == null) {
            return 0.0;
        }

        if (bestTenukiMove == null) {
            // No moves are tenuki move
            // This means the position is very urgent
            return maxUrgency;
        }

        // Calculate urgency as the score difference
        double score = bestMove.scoreLead;
        double tenukiScore = bestTenukiMove.scoreLead;
        double urgency = Math.abs(score - tenukiScore);

        return urgency;
    }

    /**
     * Check if KataGo/human policy wants to tenuki
     * Uses humanPolicy if available, otherwise falls back to moveInfos.
     * Checks against the last N moves to determine if a move is tenuki.
     * Conditions:
     * 1. Best move (by humanPolicy) is far from recent moves (distance check)
     * 2. All high policy moves are tenuki
     * 3. Not a ko situation (ko threats don't count as tenuki)
     *
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
        List<Point> recentMoves = getRecentMoves(node, tenukiHistoryMoves);

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
            debugInfo.append(String.format("Best humanPolicy move %s is not tenuki;", bestMoveStr));
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
            if (candidate.policy < minHumanPolicy) {
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
        return distance >= tenukiDistanceThreshold;
    }

    /**
     * Check if there's a high humanPolicy move that's not a tenuki.
     * Used when there are no sente moves, but we still want to continue
     * if there's a move humans would likely play.
     *
     * @param node Current node
     * @return true if there's a high policy non-tenuki move
     */
    private boolean hasHighPolicyMove(Node node) {
        if (node.kres == null) {
            return false;
        }

        Point currentMove = node.findMove();
        if (currentMove == null) {
            return false;
        }

        // Use humanPolicy if available, otherwise fall back to regular policy
        List<Double> policy = selectPolicy(node.kres);
        if (policy == null) {
            return false;
        }

        // Get top policy moves
        List<KataAnalysisResult.Policy> top = node.kres.getTopPolicy(5, policy);

        for (var pol : top) {
            // Skip if tenuki
            Point candidateMove = new Point(pol.x, pol.y);
            if (isTenuki(currentMove, candidateMove)) {
                continue;
            }

            if (pol.policy >= minHumanPolicy) {
                debugInfo.append(String.format("High policy move: %s (hp=%.2f);",
                    Intersection.toGTPloc(pol.x, pol.y), pol.policy));
                return true;
            }
        }

        return false;
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

        // Use moveInfos directly (already searched by KataGo, guaranteed to have PV)
        // And sente checking is objective so not using humanPolicy here
        List<MoveInfo> moveInfos = node.kres.moveInfos;

        int senteCount = 0;
        int checkedCount = 0;
        List<String> senteMoves = new ArrayList<>();
        List<String> goteMoves = new ArrayList<>();

        for (MoveInfo moveInfo : moveInfos) {
            if (checkedCount >= maxSenteCandidates) {
                break;
            }

            Point candidatePoint = Intersection.gtp2point(moveInfo.move);

            // Skip if the candidate itself is a tenuki
            if (isTenuki(currentMove, candidatePoint)) {
                continue;
            }

            checkedCount++;

            if (moveInfo.pv == null || moveInfo.pv.size() < 2) {
                continue;  // No PV info for this move
            }

            // Check opponent's response using PV (principal variation)
            // pv[0] is our move, pv[1] is opponent's response
            String opponentResponseMove = moveInfo.pv.get(1);
            Point opponentResponse = Intersection.gtp2point(opponentResponseMove);

            // If opponent's response is not tenuki, this is a sente move
            if (!isTenuki(candidatePoint, opponentResponse)) {
                senteCount++;
                senteMoves.add(String.format("%s->%s", moveInfo.move, opponentResponseMove));
            } else {
                goteMoves.add(String.format("%s->%s", moveInfo.move, opponentResponseMove));
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

        if (senteCount > 0) {
            debugInfo.append(String.format("HasSente(%s);", movesInfo));
        } else {
            debugInfo.append(String.format("NoSente(%s);", movesInfo));
        }

        return senteCount > 0;

        // --- Old implementation using humanPolicy ---
        // Use humanPolicy if available, otherwise fall back to regular policy
        // List<Double> policy = selectPolicy(node.kres);
        // if (policy == null || node.kres.moveInfos == null || node.kres.moveInfos.isEmpty()) {
        //     return false;
        // }
        // // Get top moves sorted by policy
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
     * Checks if the last move is related to ko.
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

        // Check parent node if available - previous move might have been a ko
        if (node.mom != null && node.mom.board != null) {
            Point prevMove = node.mom.findMove();
            if (prevMove != null && node.mom.board.isKo(prevMove)) {
                return true;
            }
        }

        return false;
    }
}
