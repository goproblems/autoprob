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

        // if not an end move, we can add possible response moves from katago
        if (result.endness < 0.0) {
            if (humanRank.equals("ai")) {
                addResponseResults(brain, node, root, rootKata, result, results, endKata, humanRank);
            }
            else {
                addResponseResultsHumanRank(brain, node, root, rootKata, result, results, endKata, humanRank);
            }
        }

        return results.toArray(new AnalysisResult[0]);
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

        if (node.depth <= MIN_DEPTH_FOR_ENDNESS)
            return MIN_ENDNESS;

        // Success - player move with positive score AND gained advantage from root
        boolean isPlayerMove = (node.getToMove() != root.getToMove());
        double scoreDelta = result.score - rootKata.blackScore(); // From black's perspective
        boolean hasAdvantage = (root.getToMove() == Intersection.BLACK && scoreDelta > 0) ||
                               (root.getToMove() == Intersection.WHITE && scoreDelta < 0);

        if (isPlayerMove &&
            (result.score > 0 && root.getToMove() == Intersection.BLACK ||
                result.score <= 0 && root.getToMove() == Intersection.WHITE) &&
            hasAdvantage) {
            debugInfo.append("Endness: positive score on player move and has advantage");
            return MAX_ENDNESS;
        }

        // Failure - significant score drop
        double drop = Math.abs(result.score - rootKata.blackScore());
        if (drop >= SCORE_DROP_THRESHOLD) {
            debugInfo.append("Endness: significant score drop;");
            return MAX_ENDNESS;
        }

        double endness = MIN_ENDNESS;

        // Value of a tenuki - check if KataGo wants to tenuki
        if (wantsTenuki(node)) {
            debugInfo.append("Endness: computer wants to tenuki;");
            return MAX_ENDNESS;
        }

        // Depth of tree - deeper means more likely to end (gentle acceleration)
        // About 30 moves beyond MIN_DEPTH to go from -1.0 to 0.0
        final double DEPTH_TARGET_MOVES = 30.0;  // target moves
        final double DEPTH_POWER = 1.1; // power for gentle acceleration
        int depthBeyondMin = node.depth - MIN_DEPTH_FOR_ENDNESS;
        double depthRatio = depthBeyondMin / DEPTH_TARGET_MOVES;
        double depthFactor = Math.pow(depthRatio, DEPTH_POWER);
        endness += depthFactor;
        debugInfo.append(String.format("DepthFactor: %.2f;", depthFactor));

        // Sente moves - check if there are any sente moves remaining
        if (!hasSenteMoves(node)) {
            debugInfo.append("Endness: no sente moves remaining;");
            return MAX_ENDNESS;
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
     * @return urgency value: higher means more urgent to continue locally
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
     * Check if KataGo wants to tenuki (play elsewhere from current dispute area).
     * Conditions:
     * 1. Best move is far from the last move (distance check)
     * 2. Best move has significant lead over second best
     * 3. Not a ko situation (ko threats don't count as tenuki)
     *
     * @param node Current node
     * @return true if KataGo wants to tenuki, false otherwise
     */
    private boolean wantsTenuki(Node node) {
        final double SIGNIFICANT_SCORE_LEAD = 2.0;     // score lead over second best
        final double SIGNIFICANT_WINRATE_LEAD = 0.05;  // winrate lead over second best
        final double SIGNIFICANT_PRIOR_LEAD = 0.15;    // policy (prior) lead over second best
        final int SIGNIFICANT_VISIT_RATIO = 4;         // visits ratio over second best

        Point currentMove = node.findMove();
        if (currentMove == null || node.kres == null ||
            node.kres.moveInfos == null || node.kres.moveInfos.isEmpty()) {
            return false;
        }

        // Check for ko situation first - ko threats should not count as tenuki
        if (isKoSituation(node)) {
            debugInfo.append("Ko situation, ignore distance;");
            return false;
        }

        List<MoveInfo> moves = node.kres.moveInfos;
        MoveInfo nextBestMove = moves.get(0);

        // Check distance of best move
        Point nextBestMovePoint = Intersection.gtp2point(nextBestMove.move);

        // best move must be far away
        if (!isTenuki(currentMove, nextBestMovePoint)) {
            return false;
        }

        double distance = Math.sqrt(
            Math.pow(nextBestMovePoint.x - currentMove.x, 2) +
            Math.pow(nextBestMovePoint.y - currentMove.y, 2)
        );

        // If only one candidate move
        if (moves.size() < 2) {
            debugInfo.append(String.format("Tenuki(dist=%.1f,only);", distance));
            return true;
        }

        // Second condition: best move(the tenuki) must have significant lead over second best
        // Check multiple metrics to determine the lead
        MoveInfo secondBest = moves.get(1);
        double scoreDiff = Math.abs(nextBestMove.scoreLead - secondBest.scoreLead);
        double winrateDiff = nextBestMove.winrate - secondBest.winrate;
        double priorDiff = nextBestMove.prior - secondBest.prior;
        int visitRatio = 1;
        if (nextBestMove.visits != null && secondBest.visits != null && secondBest.visits > 0) {
            visitRatio = nextBestMove.visits / secondBest.visits;
        }

        // If any metric shows significant lead, accept the tenuki
        if (scoreDiff >= SIGNIFICANT_SCORE_LEAD ||
            winrateDiff >= SIGNIFICANT_WINRATE_LEAD ||
            priorDiff >= SIGNIFICANT_PRIOR_LEAD ||
            visitRatio >= SIGNIFICANT_VISIT_RATIO) {
            debugInfo.append(String.format("Tenuki(dist=%.1f,ΔS=%.1f,ΔW=%.2f,ΔP=%.2f,VR=%d);",
                distance, scoreDiff, winrateDiff, priorDiff, visitRatio));
            return true;
        }

        // Distance is far but second best is competitive
        debugInfo.append(String.format("Wants tenuki but second best is competitive(dist=%.1f,ΔS=%.1f,ΔW=%.2f,ΔP=%.2f,VR=%d);",
            distance, scoreDiff, winrateDiff, priorDiff, visitRatio));
        return false;
    }

    /**
     * Check if the distance between two points constitutes a tenuki.
     *
     * @param from Starting point
     * @param to Destination point
     * @return true if the distance is >= TENUKI_DISTANCE_THRESHOLD
     */
    private boolean isTenuki(Point from, Point to) {
        final double TENUKI_DISTANCE_THRESHOLD = 5;
        double distance = Math.sqrt(
            Math.pow(to.x - from.x, 2) +
            Math.pow(to.y - from.y, 2)
        );
        return distance >= TENUKI_DISTANCE_THRESHOLD;
    }

    /**
     * Check if there are any sente moves remaining in the current position.
     * A move is considered sente if the opponent must respond locally (not tenuki).
     *
     * Algorithm:
     * 1. Get all non-tenuki candidate moves in current position
     * 2. For each candidate, check opponent's response in PV
     * 3. If opponent's response is not tenuki, it's a sente move
     * 4. Return true if at least one sente move exists
     *
     * @param node Current node
     * @return true if there are sente moves, false if all moves allow tenuki
     */
    private boolean hasSenteMoves(Node node) {
        if (node.kres == null || node.kres.moveInfos == null || node.kres.moveInfos.isEmpty()) {
            return false;
        }

        Point currentMove = node.findMove();
        if (currentMove == null) {
            return false;
        }

        List<MoveInfo> moves = node.kres.moveInfos;
        int senteCount = 0;
        int checkedCount = 0;

        // Check top candidate moves (limit to avoid performance issues)
        final int MAX_CANDIDATES_TO_CHECK = 5;
        int candidatesToCheck = Math.min(moves.size(), MAX_CANDIDATES_TO_CHECK);

        for (int i = 0; i < candidatesToCheck; i++) {
            MoveInfo candidateMove = moves.get(i);

            // Skip if the candidate itself is a tenuki
            Point candidatePoint = Intersection.gtp2point(candidateMove.move);
            if (isTenuki(currentMove, candidatePoint)) {
                continue;  // This candidate is already a tenuki, skip
            }

            checkedCount++;

            // Check opponent's response using PV (principal variation)
            if (candidateMove.pv != null && candidateMove.pv.size() >= 2) {
                // pv[0] is our move, pv[1] is opponent's response
                Point opponentResponse = Intersection.gtp2point(candidateMove.pv.get(1));

                // If opponent's response is not tenuki, this is a sente move
                if (!isTenuki(candidatePoint, opponentResponse)) {
                    // Check humanPolicy if available
                    if (node.kres.humanPolicy != null) {
                        int idx = candidatePoint.x + candidatePoint.y * 19;
                        double humanPolicyValue = node.kres.humanPolicy.get(idx);
                        if (humanPolicyValue < minHumanPolicy) {
                            // humanPolicy too low, don't count as sente
                            System.out.println("too low human policy for sente move: " + candidateMove.move + " human pol: " + df.format(humanPolicyValue) + " pol: " + df.format(candidateMove.prior));
                            continue;
                        }
                    }

                    senteCount++;
                    //TODO: record which move is sente for debugging
                    double responseDistance = Math.sqrt(
                        Math.pow(opponentResponse.x - candidatePoint.x, 2) +
                        Math.pow(opponentResponse.y - candidatePoint.y, 2)
                    );
                    debugInfo.append(String.format("Sente move found: %s (resp dist=%.1f);",
                        candidateMove.move, responseDistance));
                }
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
