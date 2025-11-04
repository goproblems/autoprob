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

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
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

    public Analysis(Properties props, KataBrain brain) throws Exception {
        this.props = Objects.requireNonNull(props, "props");
        this.brain = brain;
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
        }

        // first we analyze the root position, establish a baseline for score and more
        KataAnalysisResult rootKata = nodeAnalyzer.analyzeNode(brain, root, visits, null, humanRank);

        // play the moves in the path, get a new position from that
        Node node = addPath(root, request.path);
        // analyze the parent node, so we know direct loss for the last move
        KataAnalysisResult momKata = nodeAnalyzer.analyzeNode(brain, node.mom, visits, null, humanRank);

        // analyze the end position after the path
        KataAnalysisResult endKata = nodeAnalyzer.analyzeNode(brain, node, visits, null, humanRank);

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

        // let's decide if this is a good end move
        result.endness = -1.0; // default to not an end move
        // we only end on a human move if it's success
        if (node.depth > 5) {
            // quick hack: end now with success if position still winning
            // TODO: improve this logic
            if (result.score > 0 && root.getToMove() == Intersection.BLACK ||
                result.score <= 0 && root.getToMove() == Intersection.WHITE) {
                result.endness = 1.0;
            }
        }

        // make extendable list of possible results
        ArrayList<AnalysisResult> results = new ArrayList<>();
        results.add(result);

        // if not an end move, we can add possible response moves from katago
        if (result.endness < 0.0) {
            if (humanRank.equals("ai")) {
                addResponseResults(brain, node, rootKata, result, results, endKata);
            }
            else {
                addResponseResultsHumanRank(brain, node, rootKata, result, results, endKata);
            }
        }

        return results.toArray(new AnalysisResult[0]);
    }

    private void addResponseResultsHumanRank(KataBrain brain, Node node, KataAnalysisResult rootKata, AnalysisResult result, ArrayList<AnalysisResult> results, KataAnalysisResult endKata) {
        // use katago human-like policy results
        List<KataAnalysisResult.Policy> top = endKata.getTopPolicy(10, endKata.humanPolicy); // gets all, sorted
        double minHumanPolicy = Double.parseDouble(props.getProperty("scenario.min_response_policy", "0.05"));
        // run through these in order, if they are high enough policy and in a good location, add to responses
        for (var pol : top) {
            String mv = Intersection.toGTPloc(pol.x, pol.y);
            System.out.println("human response: " + mv + " pol: " + df.format(pol.policy));
            if (pol.policy < minHumanPolicy) {
                System.out.println("  too low policy, skipping");
                continue;
            }

            // TODO: ensure not tenuki

            // TODO: do a new katago analysis for this move to get real score and loss
            AnalysisResult responseResult = new AnalysisResult();
            responseResult.path = result.path + "," + mv;
            responseResult.rank = result.rank;
            responseResult.score = result.score; // TODO: placeholder
            responseResult.loss = 0.0; // TODO: placeholder
            responseResult.katagoPlayouts = 1; // TODO: placeholder
            responseResult.katagoWeightsFile = result.katagoWeightsFile;
            responseResult.weight = pol.policy; // from human-like policy

            result.endness = -1.0; // default to not an end move
            if (node.depth > 5) {
                // quick hack: end now with failure if a big score drop
                double minDrop = 15.0;
                double drop = Math.abs(responseResult.score - rootKata.blackScore());
                if (drop >= minDrop) {
                    responseResult.endness = 1.0;
                }
            }

            results.add(responseResult);
        }
    }

    private void addResponseResults(KataBrain brain, Node node, KataAnalysisResult rootKata, AnalysisResult result, ArrayList<AnalysisResult> results, KataAnalysisResult endKata) {
        // for now just use previous kata results to get possible moves
        // TODO: add multiple
        // TODO: don't add tenukis
        MoveInfo move = endKata.moveInfos.get(0);
        AnalysisResult responseResult = new AnalysisResult();
        responseResult.path = result.path + "," + move.move;
        responseResult.rank = result.rank;
        responseResult.score = move.scoreLead;
        responseResult.loss = move.scoreLead - endKata.blackScore();
        responseResult.katagoPlayouts = move.visits;
        responseResult.katagoWeightsFile = result.katagoWeightsFile;
        responseResult.weight = (double)move.visits / (double)endKata.rootInfo.visits; // simple for now: fraction of playouts

        // TODO: real endness logic
        result.endness = -1.0; // default to not an end move
        if (node.depth > 5) {
            // quick hack: end now with failure if a big score drop
            double minDrop = 15.0;
            double drop = Math.abs(responseResult.score - rootKata.blackScore());
            if (drop >= minDrop) {
                responseResult.endness = 1.0;
            }
        }

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
}
