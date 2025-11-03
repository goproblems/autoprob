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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * Orchestrates running KataGo analysis for API scenarios.
 */
public class Analysis implements AutoCloseable {
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
     */
    public AnalysisResult analyze(AnalysisRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.scenario, "request.scenario");
        if (request.scenario.sgf == null || request.scenario.sgf.isBlank()) {
            throw new IllegalArgumentException("Scenario SGF is required");
        }

        var nodeAnalyzer = new NodeAnalyzer(props);

        Node root = parser.parse(request.scenario.sgf);
        applyToMovePreference(root, request.scenario.toMove);

        List<PathMove> pathMoves = parsePath(root, request.path);

        int visits = determineVisits();
        KataAnalysisResult rootResult = nodeAnalyzer.analyzeNode(brain, root, visits, null, DEFAULT_HUMAN_RANK);
        double baselineScore = rootResult.blackScore();

        KataAnalysisResult finalResult = rootResult;
        List<Node> addedNodes = Collections.emptyList();
        if (!pathMoves.isEmpty()) {
            addedNodes = applyMoves(root, pathMoves);
            Node terminal = addedNodes.get(addedNodes.size() - 1);
            finalResult = nodeAnalyzer.analyzeNode(brain, terminal, visits, null, DEFAULT_HUMAN_RANK);
        }

        try {
            AnalysisResult result = new AnalysisResult();
            result.path = request.path;
            result.rank = request.difficulty;
            result.score = finalResult.blackScore();
            result.loss = finalResult.blackScore() - baselineScore;
            result.katagoPlayouts = finalResult.rootInfo != null ? finalResult.rootInfo.visits : null;
            result.katagoWeightsFile = props.getProperty("kata.model");

            MoveInfo candidateInfo = firstMoveInfo(rootResult, pathMoves);
            if (candidateInfo != null) {
                result.weight = candidateInfo.weight;
                result.extraInfo = candidateInfo.extString();
            }

            return result;
        } finally {
            removeMoves(addedNodes);
        }
    }

    @Override
    public void close() {
        if (brain != null) {
            brain.stopKataBrain();
        }
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

    private List<PathMove> parsePath(Node root, String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        if (path.length() % 2 != 0) {
            throw new IllegalArgumentException("Path must contain pairs of coordinates");
        }
        int boardSize = root.board.boardX;
        List<PathMove> moves = new ArrayList<>(path.length() / 2);
        for (int i = 0; i < path.length(); i += 2) {
            char colToken = path.charAt(i);
            char rowToken = path.charAt(i + 1);
            int x = decodeSgfCoordinate(colToken);
            int y = decodeSgfCoordinate(rowToken);
            if (x < 0 || y < 0 || x >= boardSize || y >= boardSize) {
                throw new IllegalArgumentException("Move out of bounds in path: " + path);
            }
            String gtp = Intersection.toGTPloc(x, y, boardSize);
            Point point = new Point(x, y);
            moves.add(new PathMove(gtp, point));
        }
        return moves;
    }

    private int decodeSgfCoordinate(char token) {
        return Character.toLowerCase(token) - 'a';
    }

    private List<Node> applyMoves(Node root, List<PathMove> moves) throws Exception {
        List<Node> added = new ArrayList<>(moves.size());
        Node current = root;
        for (PathMove move : moves) {
            Node next = current.addBasicMove(move.point().x, move.point().y);
            added.add(next);
            current = next;
        }
        return added;
    }

    private void removeMoves(List<Node> addedNodes) {
        if (addedNodes == null || addedNodes.isEmpty()) {
            return;
        }
        for (int i = addedNodes.size() - 1; i >= 0; i--) {
            Node node = addedNodes.get(i);
            if (node != null && node.mom != null) {
                node.mom.removeChildNode(node);
            }
        }
    }

    private MoveInfo firstMoveInfo(KataAnalysisResult rootResult, List<PathMove> pathMoves) {
        if (rootResult.moveInfos == null || rootResult.moveInfos.isEmpty() || pathMoves.isEmpty()) {
            return null;
        }
        String firstMove = pathMoves.get(0).gtp();
        for (MoveInfo moveInfo : rootResult.moveInfos) {
            if (firstMove.equalsIgnoreCase(moveInfo.move)) {
                return moveInfo;
            }
        }
        return null;
    }

    private void applyToMovePreference(Node root, String toMove) {
        if (toMove == null) {
            return;
        }
        if (toMove.equalsIgnoreCase("w")) {
            root.defaultToMoveColor = Intersection.WHITE;
        } else if (toMove.equalsIgnoreCase("b")) {
            root.defaultToMoveColor = Intersection.BLACK;
        }
    }

    private record PathMove(String gtp, Point point) {}
}
