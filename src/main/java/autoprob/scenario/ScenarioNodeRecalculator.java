package autoprob.scenario;

import autoprob.ApiClient;
import autoprob.KataBrain;
import autoprob.api.AnalysisRequest;
import autoprob.api.AnalysisResult;
import autoprob.api.ScenarioListResponse;
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
    private static final int NODE_PAGE_LIMIT = 500;
    private static final int DEFAULT_SCENARIO_LIMIT = 100;
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String RED = "\033[31m";
    private static final String RESET = "\033[0m";

    private final Properties props;
    private final ApiClient apiClient = new ApiClient();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ScenarioNodeRecalculator(Properties props) {
        this.props = props;
    }

    public void run() throws Exception {
        Integer scenarioId = readScenarioId();
        String difficulty = readDifficultyFilter();

        int processedScenarios = 0;
        int failedScenarios = 0;
        int processedNodes = 0;
        int submittedResults = 0;
        int failedNodes = 0;

        KataBrain brain = new KataBrain(props);
        try {
            if (scenarioId != null) {
                AnalysisRequest.Scenario scenario = fetchScenario(scenarioId);
                ScenarioRecalculationResult result = recalculateScenario(scenario, difficulty, brain);
                processedScenarios++;
                processedNodes += result.processedNodes();
                submittedResults += result.submittedResults();
                failedNodes += result.failedNodes();
            } else {
                System.out.println("No scenarioid provided; recalculating all alive scenarios below client version "
                    + ScenarioHandler.CLIENT_VERSION + " (node limit=" + NODE_PAGE_LIMIT
                    + (difficulty == null ? "" : ", difficulty=" + difficulty) + ")");
                int scenarioLimit = Integer.parseInt(props.getProperty("recalculate.scenarios.limit", String.valueOf(DEFAULT_SCENARIO_LIMIT)));
                int scenarioOffset = 0;

                while (true) {
                    ScenarioListResponse page = fetchScenarios(scenarioLimit, scenarioOffset);
                    List<AnalysisRequest.Scenario> scenarios = page.entries == null ? List.of() : page.entries;
                    if (scenarios.isEmpty()) {
                        System.out.println("No more alive scenarios to recalculate.");
                        break;
                    }

                    for (AnalysisRequest.Scenario scenario : scenarios) {
                        try {
                            ScenarioRecalculationResult result = recalculateScenario(scenario, difficulty, brain);
                            processedScenarios++;
                            processedNodes += result.processedNodes();
                            submittedResults += result.submittedResults();
                            failedNodes += result.failedNodes();
                        } catch (Exception ex) {
                            failedScenarios++;
                            System.out.println(RED + "Failed to recalculate scenario " + scenario.id
                                + ": " + ex.getClass().getSimpleName()
                                + (ex.getMessage() == null ? "" : " - " + ex.getMessage())
                                + RESET);
                            if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                                ex.printStackTrace(System.out);
                            }
                        }
                    }

                    scenarioOffset += scenarios.size();
                    System.out.println("Processed " + processedScenarios + " scenarios, failed "
                        + failedScenarios + " scenarios. Remaining alive scenarios reported by API: "
                        + Math.max(0, page.totalRecords - scenarioOffset));
                    if (scenarioOffset >= page.totalRecords) {
                        break;
                    }
                }
            }
        } finally {
            brain.stopKataBrain();
        }

        System.out.println("Recalculation complete. Processed " + processedScenarios
            + " scenarios, failed " + failedScenarios + " scenarios. Processed " + processedNodes
            + " nodes, submitted " + submittedResults + " results, failed " + failedNodes + " nodes.");
    }

    private record ScenarioRecalculationResult(int processedNodes, int submittedResults, int failedNodes) {}

    private ScenarioRecalculationResult recalculateScenario(AnalysisRequest.Scenario scenario,
                                                            String difficulty, KataBrain brain) throws Exception {
        int scenarioId = scenario.id;
        validateScenario(scenario, scenarioId);
        System.out.println("Recalculating scenario " + scenarioId + " nodes below client version "
            + ScenarioHandler.CLIENT_VERSION + " (limit=" + NODE_PAGE_LIMIT
            + (difficulty == null ? "" : ", difficulty=" + difficulty) + ")");

        Set<Integer> processedNodeIds = new HashSet<>();
        int submittedResults = 0;
        int failedNodes = 0;
        int totalNodesEstimate = 0;
        int queryOffset = 0;

        if (!hasNodeFilter()) {
            ScenarioNodeListResponse rootPage = fetchNodes(scenarioId, NODE_PAGE_LIMIT, 0, "");
            totalNodesEstimate = Math.max(totalNodesEstimate, processedNodeIds.size() + rootPage.totalRecords);
            ProcessNodesResult rootResult = processNodes(scenario, rootPage.entries, brain, processedNodeIds, totalNodesEstimate);
            submittedResults += rootResult.submittedResults();
            failedNodes += rootResult.failedNodes();
        }

        while (true) {
            ScenarioNodeListResponse page = fetchNodes(scenarioId, NODE_PAGE_LIMIT, queryOffset, null);
            List<ScenarioNodeEntry> entries = page.entries == null ? List.of() : page.entries;
            if (entries.isEmpty()) {
                if (queryOffset == 0) {
                    System.out.println("No more nodes to recalculate for scenario " + scenarioId + ".");
                } else {
                    System.out.println("No more nodes to recalculate for scenario " + scenarioId
                        + " after skipping nodes already attempted in this run.");
                }
                break;
            }

            totalNodesEstimate = Math.max(totalNodesEstimate, processedNodeIds.size() + page.totalRecords);
            int before = processedNodeIds.size();
            ProcessNodesResult pageResult = processNodes(scenario, entries, brain, processedNodeIds, totalNodesEstimate);
            submittedResults += pageResult.submittedResults();
            failedNodes += pageResult.failedNodes();

            System.out.println("Scenario " + scenarioId + ": processed " + processedNodeIds.size()
                + " nodes, submitted " + submittedResults + " analysis results, failed " + failedNodes
                + " nodes. Remaining reported by API after current offset: "
                + Math.max(0, page.totalRecords - queryOffset - entries.size()));

            if (processedNodeIds.size() == before) {
                queryOffset += entries.size();
                System.out.println("No new nodes processed from the latest page; advancing offset to "
                    + queryOffset + " to skip nodes already attempted in this run.");
            } else {
                queryOffset = 0;
            }
        }

        System.out.println("Scenario " + scenarioId + " recalculation complete. Processed "
            + processedNodeIds.size() + " nodes, submitted " + submittedResults
            + " results, failed " + failedNodes + " nodes.");
        return new ScenarioRecalculationResult(processedNodeIds.size(), submittedResults, failedNodes);
    }

    private Integer readScenarioId() {
        String scenarioId = props.getProperty("scenarioid");
        if (scenarioId == null || scenarioId.isBlank()) {
            scenarioId = props.getProperty("scenarioId");
        }
        if (scenarioId == null || scenarioId.isBlank()) {
            return null;
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
        validateScenario(scenario, scenarioId);
        return scenario;
    }

    private void validateScenario(AnalysisRequest.Scenario scenario, int scenarioId) {
        if (scenario == null) {
            throw new RuntimeException("Scenario API returned no data for scenario " + scenarioId);
        }
        if (scenario.id == 0) {
            scenario.id = scenarioId;
        }
        if (scenario.sgf == null || scenario.sgf.isBlank()) {
            throw new RuntimeException("Scenario " + scenarioId + " has no SGF");
        }
    }

    private ScenarioListResponse fetchScenarios(int limit, int offset) throws Exception {
        String queryString = buildScenariosQuery(limit, offset);
        System.out.println(GREEN + "Scenarios API URL: "
            + apiClient.buildUrl("api.scenarios", null, queryString, props) + RESET);
        ApiClient.ApiResponse<ScenarioListResponse> response = apiClient.makeGetRequest(
            "api.scenarios", null, queryString, ScenarioListResponse.class, props);

        if (!response.isSuccess()) {
            throw new RuntimeException("Failed to fetch scenarios: HTTP "
                + response.getStatusCode() + " - " + response.getErrorMessage());
        }

        ScenarioListResponse page = response.getData();
        if (page == null) {
            page = new ScenarioListResponse();
            page.entries = List.of();
        }
        return page;
    }

    private String buildScenariosQuery(int limit, int offset) {
        List<String> params = new ArrayList<>();
        addQueryParam(params, "limit", String.valueOf(limit));
        addQueryParam(params, "offset", String.valueOf(offset));
        addQueryParam(params, "sortBy", "id");
        addQueryParam(params, "sortDirection", "asc");
        return "?" + String.join("&", params);
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

        String difficulty = readDifficultyFilter();
        if (difficulty != null) {
            addQueryParam(params, "difficulty", difficulty);
        }
        addOptionalQueryParam(params, "responseValid");

        return "?" + String.join("&", params);
    }

    private String readDifficultyFilter() {
        String difficulty = props.getProperty("recalculate.difficulty");
        if (difficulty == null || difficulty.isBlank()) {
            difficulty = props.getProperty("difficulty");
        }
        return difficulty == null || difficulty.isBlank() ? null : difficulty;
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
            || props.getProperty("recalculate.difficulty") != null
            || props.getProperty("responseValid") != null;
    }

    private record ProcessNodesResult(int submittedResults, int failedNodes) {}

    private ProcessNodesResult processNodes(AnalysisRequest.Scenario scenario, List<ScenarioNodeEntry> entries,
                                            KataBrain brain, Set<Integer> processedNodeIds, int totalNodesEstimate) {
        if (entries == null || entries.isEmpty()) {
            return new ProcessNodesResult(0, 0);
        }

        entries.sort(Comparator
            .comparing((ScenarioNodeEntry entry) -> entry.path == null || entry.path.isEmpty() ? 0 : 1)
            .thenComparing(entry -> entry.difficulty == null ? "" : entry.difficulty)
            .thenComparing(entry -> entry.path == null ? "" : entry.path));

        int submittedResults = 0;
        int failedNodes = 0;
        for (ScenarioNodeEntry node : entries) {
            if (!processedNodeIds.add(node.id)) {
                continue;
            }

            String path = node.path == null ? "" : node.path;
            try {
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
            } catch (Exception ex) {
                failedNodes++;
                System.out.println(RED + "Failed to recalculate node " + node.id
                    + " difficulty=" + node.difficulty
                    + " path=" + (path.isEmpty() ? "<root>" : path)
                    + " version=" + node.analysisClientVersion
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
