package autoprob.scenario;

import autoprob.ApiClient;
import autoprob.KataBrain;
import autoprob.api.AnalysisRequest;
import autoprob.api.AnalysisResult;
import autoprob.api.ScenarioNodeEntry;
import autoprob.api.ScenarioNodeListResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class ScenarioNodeRecalculator {
    private static final int DEFAULT_LIMIT = 500;
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";

    private final Properties props;
    private final ApiClient apiClient = new ApiClient();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ScenarioNodeRecalculator(Properties props) {
        this.props = props;
    }

    public void run() throws Exception {
        int scenarioId = readScenarioId();
        int limit = Integer.parseInt(props.getProperty("recalculate.nodes.limit", String.valueOf(DEFAULT_LIMIT)));

        AnalysisRequest.Scenario scenario = fetchScenario(scenarioId);
        System.out.println("Recalculating scenario " + scenarioId + " nodes below client version "
            + ScenarioHandler.CLIENT_VERSION + " (limit=" + limit + ")");

        Set<Integer> processedNodeIds = new HashSet<>();
        int processedNodes = 0;
        int submittedResults = 0;
        int totalNodesEstimate = 0;

        KataBrain brain = new KataBrain(props);
        try {
            if (!hasNodeFilter()) {
                ScenarioNodeListResponse rootPage = fetchNodes(scenarioId, limit, 0, "");
                totalNodesEstimate = Math.max(totalNodesEstimate, processedNodeIds.size() + rootPage.totalRecords);
                submittedResults += processNodes(scenario, rootPage.entries, brain, processedNodeIds, totalNodesEstimate);
                processedNodes = processedNodeIds.size();
            }

            while (true) {
                ScenarioNodeListResponse page = fetchNodes(scenarioId, limit, 0, null);
                List<ScenarioNodeEntry> entries = page.entries == null ? List.of() : page.entries;
                if (entries.isEmpty()) {
                    System.out.println("No more nodes to recalculate.");
                    break;
                }

                totalNodesEstimate = Math.max(totalNodesEstimate, processedNodeIds.size() + page.totalRecords);
                int before = processedNodeIds.size();
                submittedResults += processNodes(scenario, entries, brain, processedNodeIds, totalNodesEstimate);
                processedNodes = processedNodeIds.size();

                System.out.println("Processed " + processedNodes + " nodes, submitted "
                    + submittedResults + " analysis results. Remaining reported by API: "
                    + Math.max(0, page.totalRecords - entries.size()));

                if (processedNodeIds.size() == before) {
                    System.out.println("No new nodes processed from the latest page; stopping to avoid repeating the same page.");
                    break;
                }
            }
        } finally {
            brain.stopKataBrain();
        }

        System.out.println("Recalculation complete. Processed " + processedNodes
            + " nodes and submitted " + submittedResults + " results.");
    }

    private int readScenarioId() {
        String scenarioId = props.getProperty("scenarioid");
        if (scenarioId == null || scenarioId.isBlank()) {
            scenarioId = props.getProperty("scenarioId");
        }
        if (scenarioId == null || scenarioId.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: scenarioid. Usage: cmd=recalculatenodes scenarioid=40");
        }
        return Integer.parseInt(scenarioId);
    }

    private AnalysisRequest.Scenario fetchScenario(int scenarioId) throws Exception {
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("id", String.valueOf(scenarioId));

        ApiClient.ApiResponse<AnalysisRequest.Scenario> response = apiClient.makeGetRequest(
            "api.scenario", pathParams, null, AnalysisRequest.Scenario.class, props);

        if (!response.isSuccess()) {
            throw new RuntimeException("Failed to fetch scenario " + scenarioId + ": HTTP "
                + response.getStatusCode() + " - " + response.getErrorMessage());
        }

        AnalysisRequest.Scenario scenario = response.getData();
        if (scenario == null) {
            throw new RuntimeException("Scenario API returned no data for scenario " + scenarioId);
        }
        if (scenario.id == 0) {
            scenario.id = scenarioId;
        }
        if (scenario.sgf == null || scenario.sgf.isBlank()) {
            throw new RuntimeException("Scenario " + scenarioId + " has no SGF");
        }
        return scenario;
    }

    private ScenarioNodeListResponse fetchNodes(int scenarioId, int limit, int offset, String exactPathOverride) throws Exception {
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("id", String.valueOf(scenarioId));

        String queryString = buildNodesQuery(limit, offset, exactPathOverride);
        System.out.println(GREEN + "Nodes API URL: "
            + apiClient.buildUrl("api.scenario.nodes", pathParams, queryString, props) + RESET);
        ApiClient.ApiResponse<ScenarioNodeListResponse> response = apiClient.makeGetRequest(
            "api.scenario.nodes", pathParams, queryString, ScenarioNodeListResponse.class, props);

        if (!response.isSuccess()) {
            throw new RuntimeException("Failed to fetch scenario nodes: HTTP "
                + response.getStatusCode() + " - " + response.getErrorMessage());
        }

        ScenarioNodeListResponse page = response.getData();
        if (page == null) {
            page = new ScenarioNodeListResponse();
            page.entries = List.of();
        }
        return page;
    }

    private String buildNodesQuery(int limit, int offset, String exactPathOverride) {
        List<String> params = new ArrayList<>();
        addQueryParam(params, "isAnalyzed", "true");
        addQueryParam(params, "analysisClientVersionLessThan", String.valueOf(ScenarioHandler.CLIENT_VERSION));
        addQueryParam(params, "limit", String.valueOf(limit));
        addQueryParam(params, "offset", String.valueOf(offset));

        String exactPath = exactPathOverride != null ? exactPathOverride : props.getProperty("path");
        if (exactPath != null) {
            addQueryParam(params, "path", exactPath);
        } else {
            addOptionalQueryParam(params, "pathPrefix");
        }

        addOptionalQueryParam(params, "difficulty");
        addOptionalQueryParam(params, "responseValid");

        return "?" + String.join("&", params);
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

    private boolean hasNodeFilter() {
        return props.getProperty("path") != null
            || props.getProperty("pathPrefix") != null
            || props.getProperty("difficulty") != null
            || props.getProperty("responseValid") != null;
    }

    private int processNodes(AnalysisRequest.Scenario scenario, List<ScenarioNodeEntry> entries,
                             KataBrain brain, Set<Integer> processedNodeIds, int totalNodesEstimate) throws Exception {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }

        entries.sort(Comparator
            .comparing((ScenarioNodeEntry entry) -> entry.path == null || entry.path.isEmpty() ? 0 : 1)
            .thenComparing(entry -> entry.difficulty == null ? "" : entry.difficulty)
            .thenComparing(entry -> entry.path == null ? "" : entry.path));

        int submittedResults = 0;
        for (ScenarioNodeEntry node : entries) {
            if (!processedNodeIds.add(node.id)) {
                continue;
            }

            String path = node.path == null ? "" : node.path;
            printProgressBar(processedNodeIds.size(), totalNodesEstimate, node);
            System.out.println("Recalculating node " + node.id + " difficulty=" + node.difficulty
                + " path=" + (path.isEmpty() ? "<root>" : path)
                + " version=" + node.analysisClientVersion);

            AnalysisRequest request = new AnalysisRequest();
            request.scenario = scenario;
            request.path = path;
            request.difficulty = node.difficulty;
            request.isPrecalculate = false;

            Analysis analysis = new Analysis(props, brain);
            AnalysisResult[] results = analysis.analyze(request);
            if (results == null || results.length == 0) {
                throw new RuntimeException("No analysis results for node " + node.id);
            }

            submitResults(scenario.id, results);
            submittedResults += results.length;
        }
        return submittedResults;
    }

    private void printProgressBar(int current, int total, ScenarioNodeEntry node) {
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
            String path = node.path == null || node.path.isEmpty() ? "<root>" : node.path;
            bar.append(" node=").append(node.id)
                .append(" difficulty=").append(node.difficulty)
                .append(" path=").append(path);
        }
        System.out.println(bar);
    }

    private void submitResults(int scenarioId, AnalysisResult[] results) throws Exception {
        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("scenarioId", String.valueOf(scenarioId));

        RecalculateSubmitBody body = new RecalculateSubmitBody();
        body.source = "recalculate";
        body.clientVersion = ScenarioHandler.CLIENT_VERSION;
        body.results = results;

        String requestBody = gson.toJson(body);
        if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
            System.out.println("Submitting recalculation JSON: " + requestBody);
        } else {
            System.out.println("Submitting " + results.length + " recalculated results");
        }

        ApiClient.ApiResponse<Object> response = apiClient.makePostRequest(
            "api.scenario.analysis_results", pathParams, requestBody, Object.class, props);

        if (!response.isSuccess()) {
            throw new RuntimeException("Failed to submit recalculated results: HTTP "
                + response.getStatusCode() + " - " + response.getErrorMessage());
        }
    }

    private static class RecalculateSubmitBody {
        String source;
        int clientVersion;
        AnalysisResult[] results;
    }
}
