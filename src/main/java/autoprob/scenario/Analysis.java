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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final int MIN_DEPTH_FOR_ENDNESS = 5;
    private static final double SCORE_DROP_THRESHOLD = 15.0;
    private static final double MAX_ENDNESS = 1.0;
    private static final double MIN_ENDNESS = -1.0;
    private static final double DEPTH_TARGET_MOVES = 30.0;

    // Controls the curve shape for depth factor: 1.0 = linear, >1.0 = slower early/faster late.
    // Does not affect the threshold (MIN_DEPTH_FOR_ENDNESS + DEPTH_TARGET_MOVES) where endness becomes positive.
    private static final double DEPTH_POWER = 1.1;

    private static final double MAX_URGENCY = 10.0;

    private static final int TENUKI_HISTORY_MOVES = 3;
    private static final double TENUKI_DISTANCE_THRESHOLD = 6.0;

    private static final int MAX_OPTIMAL_MOVES = 1;
    private static final int MAX_SENTE_CANDIDATES = 5;

    private static final int PRECALCULATION_MAX_DEPTH = 20;
    private static final int PRECALCULATION_MAX_NODES = 3000;
    private static final int PRECALCULATION_BATCH_SIZE = 10;  // Submit results every N nodes

    private final Properties props;
    private final KataBrain brain;
    private final Parser parser = new Parser();
    private final StringBuilder debugInfo = new StringBuilder();
    private final double minHumanPolicy;

    private ResultSubmitter resultSubmitter;

    @FunctionalInterface
    public interface ResultSubmitter {
        void submit(AnalysisResult[] results) throws Exception;
    }

    public Analysis(Properties props, KataBrain brain) throws Exception {
        this.props = Objects.requireNonNull(props, "props");
        this.brain = brain;
        this.minHumanPolicy = Double.parseDouble(props.getProperty("scenario.min_response_policy", "0.05"));
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

        boolean isPrecalculationMode = request.path == null || request.path.isBlank();

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
        result.extraInfo = formatExtraInfo(endKata, debugInfo.toString());

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
     * Precalculation mode: precalculate the game tree from root, following high humanPolicy moves.
     * Uses BFS with a queue to analyze nodes breadth-first.
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
        System.out.println("Precalculation mode: maxDepth=" + PRECALCULATION_MAX_DEPTH + 
            ", maxNodes=" + PRECALCULATION_MAX_NODES + ", batchSize=" + PRECALCULATION_BATCH_SIZE);

        int visits = determineVisits();
        String humanRank = normalizeRank(request.difficulty);
        int maxDepth = PRECALCULATION_MAX_DEPTH;
        int maxNodes = PRECALCULATION_MAX_NODES;

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

        // BFS queue
        Deque<PrecalcNode> queue = new ArrayDeque<>();
        queue.add(new PrecalcNode(root, rootKata, "", 0));

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
                result.extraInfo = formatExtraInfo(childKata, debugInfo.toString());
                results.add(result);
                nodesCount++;

                if (results.size() >= PRECALCULATION_BATCH_SIZE) {
                    // Always include root result in every batch submission
                    ArrayList<AnalysisResult> batch = new ArrayList<>();
                    batch.add(rootResult);
                    batch.addAll(results);
                    System.out.println("Submitting batch of " + batch.size() + " results (total: " + nodesCount + ")");
                    submitResults(batch.toArray(AnalysisResult[]::new));
                    results.clear();
                }

                if (result.endness < 0) {
                    queue.add(new PrecalcNode(childNode, childKata, path, current.depth + 1));
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
        responseResult.extraInfo = formatExtraInfo(responseKata, debugInfo.toString());

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
        responseResult.extraInfo = formatExtraInfo(responseKata, debugInfo.toString());

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

        int movesToAdd = Math.min(momKata.moveInfos.size(), MAX_OPTIMAL_MOVES);

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
            optimalResult.extraInfo = formatExtraInfo(optimalKata, debugInfo.toString());

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
        result.endness = 0.0;
        result.katagoPlayouts = rootKata.rootInfo.visits;
        result.katagoWeightsFile = weightsFile;
        result.weight = 0.0;
        result.extraInfo = formatExtraInfo(rootKata, "");
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
            // return MAX_ENDNESS;
        }

        // Failure - significant score drop (loss for the player)
        double scoreLoss = (root.getToMove() == Intersection.BLACK) ? -scoreDelta : scoreDelta;
        if (scoreLoss >= SCORE_DROP_THRESHOLD) {
            debugInfo.append(String.format("Endness: significant score loss (%.1f);", scoreLoss));
            return MAX_ENDNESS;
        }

        double endness = MIN_ENDNESS;

        // Value of a tenuki - check if KataGo wants to tenuki
        // Only check on player's move, since on computer's move, tenuki is user's choice
        if (isPlayerMove && wantsTenuki(node)) {
            if (node.depth <= MIN_DEPTH_FOR_ENDNESS) {
                debugInfo.append("Endness: computer wants tenuki but depth too low, continue;");
                return MIN_ENDNESS;
            }
            debugInfo.append("Endness: computer wants to tenuki;");
            return MAX_ENDNESS;
        }

        // Depth of tree - deeper means more likely to end (gentle acceleration)
        int depthBeyondMin = Math.max(0, node.depth - MIN_DEPTH_FOR_ENDNESS);
        double depthRatio = depthBeyondMin / DEPTH_TARGET_MOVES;
        double depthFactor = Math.pow(depthRatio, DEPTH_POWER);
        endness += depthFactor;
        debugInfo.append(String.format("DepthFactor: %.2f;", depthFactor));

        // Only check on computer move, to see if player still has sente moves to play
        if (!isPlayerMove && !hasSenteMoves(node)) {
            if (node.depth <= MIN_DEPTH_FOR_ENDNESS) {
                debugInfo.append("Endness: no sente but depth too low, continue;");
                return MIN_ENDNESS;
            }
            // No sente moves, but check if there's a high policy move worth playing
            if (!hasHighPolicyMove(node)) {
                debugInfo.append("Endness: no sente and no high policy moves;");
                return MAX_ENDNESS;
            }
            debugInfo.append("No sente but has high policy move;");
        }

        // TODO: Total loss - change to continuous value instead of threshold

        // TODO: Stones lost - judge dead stone ratio by ownership

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
            return MAX_URGENCY;
        }

        // Calculate urgency as the score difference
        double score = bestMove.scoreLead;
        double tenukiScore = bestTenukiMove.scoreLead;
        double urgency = Math.abs(score - tenukiScore);

        return urgency;
    }

    /**
     * Check if KataGo/human policy wants to tenuki (play elsewhere from current dispute area).
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
        List<Point> recentMoves = getRecentMoves(node, TENUKI_HISTORY_MOVES);

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
     * @return true if the distance is >= TENUKI_DISTANCE_THRESHOLD
     */
    private boolean isTenuki(Point from, Point to) {
        double distance = Math.sqrt(
            Math.pow(to.x - from.x, 2) +
            Math.pow(to.y - from.y, 2)
        );
        return distance >= TENUKI_DISTANCE_THRESHOLD;
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

        // Use humanPolicy if available, otherwise fall back to regular policy
        List<Double> policy = selectPolicy(node.kres);
        if (policy == null || node.kres.moveInfos == null || node.kres.moveInfos.isEmpty()) {
            return false;
        }

        // Get top moves sorted by policy
        List<KataAnalysisResult.Policy> topMoves = node.kres.getTopPolicy(10, policy);

        int senteCount = 0;
        int checkedCount = 0;

        for (var pol : topMoves) {
            if (checkedCount >= MAX_SENTE_CANDIDATES) {
                break;
            }

            // Skip if policy is too low
            if (pol.policy < minHumanPolicy) {
                continue;
            }

            Point candidatePoint = new Point(pol.x, pol.y);
            String candidateMove = Intersection.toGTPloc(pol.x, pol.y);

            // Skip if the candidate itself is a tenuki
            if (isTenuki(currentMove, candidatePoint)) {
                continue;
            }

            checkedCount++;

            // Find this move in moveInfos to get its PV
            MoveInfo moveInfo = node.kres.getMoveInfo(candidateMove);

            if (moveInfo == null || moveInfo.pv == null || moveInfo.pv.size() < 2) {
                continue;  // No PV info for this move
            }

            // Check opponent's response using PV (principal variation)
            // pv[0] is our move, pv[1] is opponent's response
            Point opponentResponse = Intersection.gtp2point(moveInfo.pv.get(1));

            // If opponent's response is not tenuki, this is a sente move
            if (!isTenuki(candidatePoint, opponentResponse)) {
                senteCount++;
                double responseDistance = Math.sqrt(
                    Math.pow(opponentResponse.x - candidatePoint.x, 2) +
                    Math.pow(opponentResponse.y - candidatePoint.y, 2)
                );
                debugInfo.append(String.format("Sente move found: %s (pol=%.2f,resp dist=%.1f);",
                    candidateMove, pol.policy, responseDistance));
            }
        }

        if (senteCount > 0) {
            debugInfo.append(String.format("Has sente: %d/%d;", senteCount, checkedCount));
            return true;
        } else {
            debugInfo.append(String.format("No sente: 0/%d;", checkedCount));
            return false;
        }
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

    /**
     * Format extra information as JSON with analysis and debug info.
     *
     * @param kataResult KataGo analysis result
     * @param debugInfo Debug messages as a string
     * @return JSON string with {"analysis": {...}, "debug": "..."}
     */
    private String formatExtraInfo(KataAnalysisResult kataResult, String debugInfo) {
        Gson gson = new Gson();

        Map<String, Object> jsonOutput = new HashMap<>();
        jsonOutput.put("analysis", kataResult);
        jsonOutput.put("debug", debugInfo != null ? debugInfo : "");

        return gson.toJson(jsonOutput);
    }
}
