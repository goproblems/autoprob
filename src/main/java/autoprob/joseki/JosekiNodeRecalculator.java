package autoprob.joseki;

import autoprob.ApiClient;
import autoprob.KataBrain;
import autoprob.QueryBuilder;
import autoprob.api.JosekiAnalysisResultData;
import autoprob.api.JosekiAnalysisSubmitBody;
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

public class JosekiNodeRecalculator {
    public static final int CLIENT_VERSION = 2;

    private static final int NODE_PAGE_LIMIT = 500;
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";
    private static final DecimalFormat DF = new DecimalFormat("0.00");

    private final Properties props;
    private final ApiClient apiClient = new ApiClient();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<Integer, Set<String>> childMovesByParentId = new HashMap<>();
    private int queryCounter = 0;

    public JosekiNodeRecalculator(Properties props) {
        this.props = props;
    }

    public void run() throws Exception {
        System.out.println("Recalculating joseki nodes below client version " + CLIENT_VERSION
            + " (limit=" + NODE_PAGE_LIMIT + ")");

        int submittedResults = 0;
        int failedNodes = 0;
        int queryOffset = 0;
        Set<Integer> processedNodeIds = new HashSet<>();

        KataBrain brain = new KataBrain(props);
        try {
            while (true) {
                JosekiNodeListResponse page = fetchNodes(NODE_PAGE_LIMIT, queryOffset);
                List<JosekiNodeEntry> entries = page.entries == null ? List.of() : page.entries;
                if (entries.isEmpty()) {
                    if (queryOffset == 0) {
                        System.out.println("No more joseki nodes to recalculate.");
                    } else {
                        System.out.println("No more joseki nodes after skipping nodes already attempted in this run.");
                    }
                    break;
                }

                int before = processedNodeIds.size();
                int totalNodesEstimate = Math.max(processedNodeIds.size() + page.totalRecords, processedNodeIds.size());
                ProcessNodesResult result = processNodes(entries, brain, processedNodeIds, totalNodesEstimate);
                submittedResults += result.submittedResults();
                failedNodes += result.failedNodes();

                System.out.println("Joseki recalculation: processed " + processedNodeIds.size()
                    + " nodes, submitted " + submittedResults + " analysis results, failed " + failedNodes
                    + " nodes. Remaining reported by API after current offset: "
                    + Math.max(0, page.totalRecords - queryOffset - entries.size()));

                if (processedNodeIds.size() == before) {
                    queryOffset += entries.size();
                    System.out.println("No new joseki nodes processed from latest page; advancing offset to "
                        + queryOffset + " to skip nodes already attempted in this run.");
                } else {
                    queryOffset = 0;
                }
            }
        } finally {
            brain.stopKataBrain();
        }

        System.out.println("Joseki recalculation complete. Processed " + processedNodeIds.size()
            + " nodes, submitted " + submittedResults + " results, failed " + failedNodes + " nodes.");
    }

    private record ProcessNodesResult(int submittedResults, int failedNodes) {}

    private ProcessNodesResult processNodes(List<JosekiNodeEntry> entries, KataBrain brain,
                                            Set<Integer> processedNodeIds, int totalNodesEstimate) {
        sortEntries(entries);

        int submittedResults = 0;
        int failedNodes = 0;
        for (JosekiNodeEntry entry : entries) {
            if (!processedNodeIds.add(entry.id)) {
                continue;
            }

            String path = entry.path == null ? "" : entry.path;
            try {
                printProgressBar(processedNodeIds.size(), totalNodesEstimate, entry);
                System.out.println("Recalculating joseki node " + entry.id
                    + " path=" + formatPath(path)
                    + " version=" + entry.analysisClientVersion);

                JosekiAnalysisResultData result = analyzeNode(entry, brain);
                submitResult(result);
                submittedResults++;
            } catch (Exception ex) {
                failedNodes++;
                System.out.println(RED + "Failed to recalculate joseki node " + entry.id
                    + " path=" + formatPath(path)
                    + " version=" + entry.analysisClientVersion
                    + ": " + ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : " - " + ex.getMessage())
                    + RESET);
                if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                    ex.printStackTrace(System.out);
                }
            }
        }
        return new ProcessNodesResult(submittedResults, failedNodes);
    }

    private void sortEntries(List<JosekiNodeEntry> entries) {
        if ("breadth".equals(props.getProperty("sort"))) {
            entries.sort(Comparator
                .comparingInt((JosekiNodeEntry entry) -> pathDepth(entry.path))
                .thenComparing(entry -> entry.path == null ? "" : entry.path));
            return;
        }

        entries.sort(Comparator
            .comparing((JosekiNodeEntry entry) -> entry.path == null || entry.path.isEmpty() ? 0 : 1)
            .thenComparing(entry -> entry.path == null ? "" : entry.path));
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

    private JosekiAnalysisResultData analyzeNode(JosekiNodeEntry entry, KataBrain brain) throws Exception {
        String path = entry.path == null ? "" : entry.path;
        Node node = buildNode(path);
        KataAnalysisResult parentResult = null;
        MoveInfo moveInfo = null;
        Double parentScore = null;

        if (node.mom != null) {
            Set<String> parentChildMoves = entry.parentId == null
                ? new LinkedHashSet<>()
                : new LinkedHashSet<>(fetchChildMoves(entry.parentId));
            String nodeMove = lastMove(path);
            if (nodeMove != null) {
                parentChildMoves.add(nodeMove);
            }
            parentResult = queryNode(brain, node.mom, "parent", parentChildMoves);
            parentScore = parentResult.blackScore();
            moveInfo = findMoveInfo(parentResult, node);
        }

        KataAnalysisResult currentResult = queryNode(brain, node, "current", fetchChildMoves(entry.id));
        double score = currentResult.blackScore();
        Double moverScoreDelta = parentScore == null ? null : moveScoreDelta(node, parentScore, score);

        JosekiAnalysisResultData result = new JosekiAnalysisResultData();
        result.path = path;
        result.score = score;
        result.loss = moverScoreDelta == null ? 0.0 : -moverScoreDelta;
        result.katagoPlayouts = Integer.parseInt(props.getProperty("joseki.visits", "1000"));
        result.katagoWeightsFile = katagoWeightsFile();
        result.prior = moveInfo == null ? null : moveInfo.prior;
        result.visits = moveInfo == null ? null : moveInfo.visits;
        result.moveOrder = moveInfo == null ? null : moveInfo.order;
        result.extraInfo = buildExtraInfo(parentScore, score, moverScoreDelta);
        result.analysis = gson.toJson(currentResult);

        System.out.println("Joseki node " + formatPath(path)
            + " score=" + DF.format(result.score)
            + " loss=" + DF.format(result.loss)
            + (result.prior == null ? "" : " prior=" + DF.format(result.prior * 1000.0))
            + (result.visits == null ? "" : " visits=" + result.visits)
            + (result.moveOrder == null ? "" : " order=" + result.moveOrder));
        return result;
    }

    private KataAnalysisResult queryNode(KataBrain brain, Node node, String role, Set<String> childMoves) throws Exception {
        QueryBuilder queryBuilder = new QueryBuilder();
        KataQuery query = queryBuilder.buildQuery(node);
        query.id = "joseki:" + (++queryCounter) + ":" + role;
        query.includePolicy = true;
        query.analyzeTurns.clear();
        query.analyzeTurns.add(0);
        query.maxVisits = Integer.parseInt(props.getProperty("joseki.visits", "1000"));

        String humanRank = props.getProperty("joseki.human_sl_rank", "3d");
        if (humanRank != null && !humanRank.isBlank()) {
            query.setHumanSLrank(humanRank.trim());
        }

        int maxMoveDistance = Integer.parseInt(props.getProperty("joseki.max_move_distance", "4"));
        if (maxMoveDistance >= 0) {
            restrictToNearbyMoves(node, query, maxMoveDistance, childMoves);
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
        return result;
    }

    private void restrictToNearbyMoves(Node node, KataQuery query, int distance, Set<String> childMoves) {
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

        if (!hasAnchorStone) {
            return;
        }

        Set<String> moves = new LinkedHashSet<>();
        for (int x = 0; x < 19; x++) {
            for (int y = 0; y < 19; y++) {
                if (allowSpots[x][y] && node.board.board[x][y].stone == Intersection.EMPTY) {
                    moves.add(Intersection.toGTPloc(x, y, 19));
                }
            }
        }
        addChildMoves(moves, node, childMoves);

        if (moves.isEmpty()) {
            return;
        }

        AllowMove allowMove = new AllowMove();
        allowMove.player = Intersection.color2katagoname(node.getToMove());
        allowMove.untilDepth = 1;
        allowMove.moves = new ArrayList<>(moves);
        query.allowMoves = new ArrayList<>();
        query.allowMoves.add(allowMove);
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

    private String buildExtraInfo(Double parentScore, double score, Double moverScoreDelta) {
        Map<String, Object> info = new HashMap<>();
        info.put("clientVersion", CLIENT_VERSION);
        info.put("parentScore", parentScore);
        info.put("score", score);
        info.put("moverScoreDelta", moverScoreDelta);
        info.put("komi", props.getProperty("joseki.komi", "6.5"));
        return gson.toJson(info);
    }

    private JosekiNodeListResponse fetchNodes(int limit, int offset) throws Exception {
        String queryString = buildNodesQuery(limit, offset);
        System.out.println(GREEN + "Joseki nodes API URL: "
            + apiClient.buildUrl("api.joseki.nodes", null, queryString, props) + RESET);

        ApiClient.ApiResponse<JosekiNodeListResponse> response = apiClient.makeGetRequest(
            "api.joseki.nodes", null, queryString, JosekiNodeListResponse.class, props);
        if (!response.isSuccess()) {
            throw new RuntimeException("Failed to fetch joseki nodes: HTTP "
                + response.getStatusCode() + " - " + response.getErrorMessage());
        }

        JosekiNodeListResponse page = response.getData();
        if (page == null) {
            page = new JosekiNodeListResponse();
            page.entries = List.of();
        }
        return page;
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
    }

    private String buildNodesQuery(int limit, int offset) {
        List<String> params = new ArrayList<>();
        addQueryParam(params, "analysisClientVersionLessThan", String.valueOf(CLIENT_VERSION));
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

    private void submitResult(JosekiAnalysisResultData result) throws Exception {
        JosekiAnalysisSubmitBody body = new JosekiAnalysisSubmitBody();
        body.source = "recalculate";
        body.clientVersion = CLIENT_VERSION;
        body.results = List.of(result);

        String requestBody = gson.toJson(body);
        if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
            System.out.println("Submitting joseki recalculation JSON: " + requestBody);
        } else {
            System.out.println("Submitting joseki recalculated result for path=" + formatPath(result.path));
        }

        ApiClient.ApiResponse<Object> response = apiClient.makePostRequest(
            "api.joseki.analysis_results", null, requestBody, Object.class, props);
        if (!response.isSuccess()) {
            throw new RuntimeException("Failed to submit joseki recalculated result: HTTP "
                + response.getStatusCode() + " - " + response.getErrorMessage());
        }
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
