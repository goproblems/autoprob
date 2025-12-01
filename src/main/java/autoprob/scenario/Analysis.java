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
import java.util.ArrayList;
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

    private final Properties props;
    private final KataBrain brain;
    private final Parser parser = new Parser();
    private final StringBuilder debugInfo = new StringBuilder();
    private final double minHumanPolicy;

    public Analysis(Properties props, KataBrain brain) throws Exception {
        this.props = Objects.requireNonNull(props, "props");
        this.brain = brain;
        this.minHumanPolicy = Double.parseDouble(props.getProperty("scenario.min_response_policy", "0.05"));
    }

    /**
     * Runs KataGo on the supplied request path and summarizes the outcome.
     * we assume that the given path is a human move. this is important.
     */
    public AnalysisResult[] analyze(AnalysisRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.scenario, "request.scenario");
        if (request.scenario.sgf == null || request.scenario.sgf.isBlank()) {
            throw new IllegalArgumentException("Scenario SGF is required");
        }

        var nodeAnalyzer = new NodeAnalyzer(props);

        Node root = parser.parse(request.scenario.sgf);
        System.out.println(root.board);
        System.out.println("To move: " + (root.getToMove() == Intersection.BLACK ? "black" : "white"));

        int visits = determineVisits();
        System.out.println("Visits: " + visits);

        String humanRank = DEFAULT_HUMAN_RANK;
        if (request.difficulty != null && !request.difficulty.isBlank()) {
            humanRank = request.difficulty;
            if (humanRank.equalsIgnoreCase("pro")) {
                humanRank = "9d";
            }
        }

        // first we analyze the root position, establish a baseline for score and more
        KataAnalysisResult rootKata = nodeAnalyzer.analyzeNode(brain, root, visits, null, humanRank);

        // play the moves in the path, get a new position from that
        Node node = addPath(root, request.path);
        // analyze the parent node, so we know direct loss for the last move
        KataAnalysisResult momKata = nodeAnalyzer.analyzeNode(brain, node.mom, visits, null, humanRank);

        // analyze the end position after the path
        KataAnalysisResult endKata = nodeAnalyzer.analyzeNode(brain, node, visits, null, humanRank);
        node.kres = endKata;

        AnalysisResult result = new AnalysisResult();
        result.path = request.path;
        result.rank = request.difficulty;
        result.score = endKata.blackScore();
        result.loss = endKata.blackScore() - momKata.blackScore();
        result.katagoPlayouts = endKata.rootInfo.visits;
        result.weight = 0.0; // not used for human move
        // get last fragment for model
        String fullModelPath = props.getProperty("kata.model");
        result.katagoWeightsFile = (fullModelPath.substring(fullModelPath.lastIndexOf('/') + 1)).substring(fullModelPath.lastIndexOf('\\') + 1);

        result.urgency = calculateUrgency(node);
        // let's decide if this is a good end move
        debugInfo.setLength(0); // Clear debug info
        result.endness = calculateEndness(result, node, root, rootKata);

        // Format extraInfo with analysis data and debug info
        result.extraInfo = formatExtraInfo(endKata, debugInfo.toString());

        // make extendable list of possible results
        ArrayList<AnalysisResult> results = new ArrayList<>();

        // Always add root node analysis for calculating total loss for scenario node
        AnalysisResult rootResult = new AnalysisResult();
        rootResult.path = "";  // root position
        rootResult.rank = request.difficulty;
        rootResult.score = rootKata.blackScore();
        rootResult.loss = 0.0;  // no loss at root
        rootResult.urgency = 0.0;
        rootResult.endness = 0.0;
        rootResult.katagoPlayouts = rootKata.rootInfo.visits;
        rootResult.katagoWeightsFile = result.katagoWeightsFile;
        rootResult.weight = 0.0;
        results.add(rootResult);

        results.add(result);

        // Add optimal moves from parent node (momKata) - these are the best moves KataGo recommends at that position
        addOptimalMoves(node.mom, momKata, root, rootKata, request.path, request.difficulty, result.katagoWeightsFile, results);

        // if not an end move, we can add possible response moves from katago
        if (result.endness < 0.0) {
            if (humanRank.equals("ai")) {
                addResponseResults(brain, node, root, rootKata, result, results, endKata, humanRank);
            }
            else {
                addResponseResultsHumanRank(brain, node, root, rootKata, result, results, endKata, humanRank);
            }
        }

        return results.toArray(AnalysisResult[]::new);
    }

    private void addResponseResultsHumanRank(KataBrain brain, Node node, Node root, KataAnalysisResult rootKata, AnalysisResult result, ArrayList<AnalysisResult> results, KataAnalysisResult endKata, String humanRank) throws Exception {
        // use katago human-like policy results
        List<KataAnalysisResult.Policy> top = endKata.getTopPolicy(10, endKata.humanPolicy); // gets all, sorted
        int visits = determineVisits();
        var nodeAnalyzer = new NodeAnalyzer(props);

        // run through these in order, if they are high enough policy and in a good location, add to responses
        for (var pol : top) {
            String mv = Intersection.toGTPloc(pol.x, pol.y);
            System.out.println("human response: " + mv + " pol: " + df.format(pol.policy));
            if (pol.policy < minHumanPolicy) {
                System.out.println("  too low policy, skipping");
                continue;
            }

            Point currentMove = node.findMove();
            if (currentMove != null) {
                Point candidateMove = new Point(pol.x, pol.y);
                if (isTenuki(currentMove, candidateMove)) {
                    System.out.println("  tenuki move, skipping");
                    continue;
                }
            }

            Integer moveVisits = null;
            for (MoveInfo mi : endKata.moveInfos) {
                if (mi.move.equals(mv)) {
                    moveVisits = mi.visits;
                    break;
                }
            }

            Node responseNode = node.addBasicMove(pol.x, pol.y);
            KataAnalysisResult responseKata = nodeAnalyzer.analyzeNode(brain, responseNode, visits, null, humanRank);
            responseNode.kres = responseKata;

            AnalysisResult responseResult = new AnalysisResult();
            responseResult.path = result.path + "," + mv;
            responseResult.rank = result.rank;
            responseResult.score = responseKata.blackScore();
            responseResult.loss = responseKata.blackScore() - endKata.blackScore();
            responseResult.urgency = calculateUrgency(responseNode);
            responseResult.katagoPlayouts = responseKata.rootInfo.visits;
            responseResult.katagoWeightsFile = result.katagoWeightsFile;
            responseResult.weight = moveVisits != null ? (double) moveVisits : 0.0; // using visits from endKata.moveInfos

            debugInfo.setLength(0);
            responseResult.endness = calculateEndness(responseResult, responseNode, root, rootKata);
            responseResult.extraInfo = formatExtraInfo(responseKata, debugInfo.toString());

            results.add(responseResult);
        }
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

        AnalysisResult responseResult = new AnalysisResult();
        responseResult.path = result.path + "," + move.move;
        responseResult.rank = result.rank;
        responseResult.score = responseKata.blackScore();
        responseResult.loss = responseKata.blackScore() - endKata.blackScore();
        responseResult.urgency = calculateUrgency(responseNode);
        responseResult.katagoPlayouts = responseKata.rootInfo.visits;
        responseResult.katagoWeightsFile = result.katagoWeightsFile;
        responseResult.weight = (double) move.visits; // using visits

        debugInfo.setLength(0);
        responseResult.endness = calculateEndness(responseResult, responseNode, root, rootKata);
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

        final int MAX_OPTIMAL_MOVES = 1;
        int movesToAdd = Math.min(momKata.moveInfos.size(), MAX_OPTIMAL_MOVES);

        // Get the parent path (path without the last move)
        String parentPath = getParentPath(path);

        int visits = determineVisits();
        var nodeAnalyzer = new NodeAnalyzer(props);
        String humanRank = rank;
        if (humanRank != null && humanRank.equalsIgnoreCase("pro")) {
            humanRank = "9d";
        }

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

            AnalysisResult optimalResult = new AnalysisResult();
            optimalResult.path = optimalPath;
            optimalResult.rank = rank;
            optimalResult.score = optimalKata.blackScore();
            optimalResult.loss = optimalKata.blackScore() - momKata.blackScore();
            optimalResult.urgency = calculateUrgency(optimalNode);
            optimalResult.katagoPlayouts = optimalKata.rootInfo.visits;
            optimalResult.katagoWeightsFile = weightsFile;
            optimalResult.weight = (double) optimalMoveVisits;

            debugInfo.setLength(0);
            optimalResult.endness = calculateEndness(optimalResult, optimalNode, root, rootKata);
            optimalResult.extraInfo = formatExtraInfo(optimalKata, "optimal_move;" + debugInfo.toString());

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
        final int MIN_DEPTH_FOR_ENDNESS = 5;
        final double SCORE_DROP_THRESHOLD = 15.0;
        final double MAX_ENDNESS = 1.0;
        final double MIN_ENDNESS = -1.0;


        // Success - player move with positive score AND gained advantage from root
        boolean isPlayerMove = (node.getToMove() != root.getToMove());
        double scoreDelta = result.score - rootKata.blackScore(); // From black's perspective
        boolean hasAdvantage = (root.getToMove() == Intersection.BLACK && scoreDelta > 0) ||
                               (root.getToMove() == Intersection.WHITE && scoreDelta < 0);

        if (isPlayerMove &&
            (result.score > 0 && root.getToMove() == Intersection.BLACK ||
                result.score <= 0 && root.getToMove() == Intersection.WHITE) &&
            hasAdvantage) {
            debugInfo.append("Endness: positive score on player move and has advantage, good move, but don't end problem for now; ");
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
            debugInfo.append("Endness: computer wants to tenuki;");
            return MAX_ENDNESS;
        }

        // Depth of tree - deeper means more likely to end (gentle acceleration)
        // About 30 moves beyond MIN_DEPTH to go from -1.0 to 0.0
        final double DEPTH_TARGET_MOVES = 30.0;  // target moves
        final double DEPTH_POWER = 1.1; // power for gentle acceleration
        int depthBeyondMin = Math.max(0, node.depth - MIN_DEPTH_FOR_ENDNESS);
        double depthRatio = depthBeyondMin / DEPTH_TARGET_MOVES;
        double depthFactor = Math.pow(depthRatio, DEPTH_POWER);
        endness += depthFactor;
        debugInfo.append(String.format("DepthFactor: %.2f;", depthFactor));

        // Only check on computer move, to see if player still has sente moves to play
        if (!isPlayerMove && !hasSenteMoves(node)) {
            // No sente moves, but check if there's a high policy move worth playing
            if (!hasHighPolicyMove(node)) {
                debugInfo.append("Endness: no sente and no high policy moves;");
                return MAX_ENDNESS;
            }
            debugInfo.append("No sente but has high policy move;");
        }

        if (node.depth <= MIN_DEPTH_FOR_ENDNESS)
            return MIN_ENDNESS;

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
        final double HIGH_URGENCY = 10.0;  // urgency when no tenuki option is available

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
            return HIGH_URGENCY;
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
        final int TENUKI_HISTORY_MOVES = 3;  // Number of recent moves to check for tenuki

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

        // Use humanPolicy if available, otherwise fall back to regular policy/moveInfos
        List<Double> policyToUse = node.kres.humanPolicy != null ? node.kres.humanPolicy : node.kres.policy;
        // if (policyToUse == null) {
        //     // Fall back to moveInfos-based logic
        //     return wantsTenukiByMoveInfos(node, recentMoves);
        // }

        // Get top policy moves sorted by humanPolicy
        List<KataAnalysisResult.Policy> topMoves = node.kres.getTopPolicy(10, policyToUse);
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
        debugInfo.append(String.format("Non tenuki moves: %s;",
            String.join(",", nonTenukiMoves)));
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
        final double TENUKI_DISTANCE_THRESHOLD = 6;
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
        List<Double> policy = node.kres.humanPolicy != null ? node.kres.humanPolicy : node.kres.policy;
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
        List<Double> policyToUse = node.kres.humanPolicy != null ? node.kres.humanPolicy : node.kres.policy;
        if (policyToUse == null || node.kres.moveInfos == null || node.kres.moveInfos.isEmpty()) {
            return false;
        }

        // Get top moves sorted by policy
        List<KataAnalysisResult.Policy> topMoves = node.kres.getTopPolicy(10, policyToUse);

        int senteCount = 0;
        int checkedCount = 0;
        final int MAX_CANDIDATES_TO_CHECK = 5;

        for (var pol : topMoves) {
            if (checkedCount >= MAX_CANDIDATES_TO_CHECK) {
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
            MoveInfo moveInfo = null;
            for (MoveInfo mi : node.kres.moveInfos) {
                if (mi.move.equals(candidateMove)) {
                    moveInfo = mi;
                    break;
                }
            }

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
