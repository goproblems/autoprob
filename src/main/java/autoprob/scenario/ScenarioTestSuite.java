package autoprob.scenario;

import autoprob.ApiClient;
import autoprob.KataBrain;
import autoprob.api.AnalysisRequest;
import autoprob.api.AnalysisResult;
import autoprob.api.ScenarioCase;
import autoprob.api.Scenario;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public class ScenarioTestSuite {
    private final Properties props;
    private final Gson gson = new Gson();
    private static final DecimalFormat df = new DecimalFormat("0.00");

    private static final double SCORE_RANGE = 0.5;
    private static final double LOSS_RANGE = 0.5;

    private static final double DEFAULT_ENDNESS_TOLERANCE = 1.0;
    private static final double DEFAULT_URGENCY_TOLERANCE = 3.0;
    private static final double DEFAULT_TOTAL_LOSS_TOLERANCE = 0.5;

    // ANSI color codes
    private static final String GREEN = "\033[32m";
    private static final String RED = "\033[31m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String RESET = "\033[0m";

    private record FailedTest(int caseId, String description, String path, int invasionId, String difficulty, List<String> failures, ScenarioCase.Tolerances tolerances, String extraInfo) {}

    public ScenarioTestSuite(Properties props) {
        this.props = props;
    }

    private List<ScenarioCase> fetchScenarioCases() throws Exception {
        ApiClient apiClient = new ApiClient();

        Type listType = new TypeToken<ArrayList<ScenarioCase>>(){}.getType();
        ApiClient.ApiResponse<List<ScenarioCase>> response = apiClient.makeGetRequest(
            "api.scenario_cases",
            null,
            null,
            listType,
            props
        );

        if (!response.isSuccess()) {
            throw new RuntimeException("Failed to fetch scenario cases: HTTP " + response.getStatusCode() +
                " - " + response.getErrorMessage());
        }

        return response.getData();
    }

    /**
     * Run API-based test suite
     */
    public void runAPISuite() throws Exception {
        System.out.println("Fetching test cases from API...");

        Integer targetScenarioId = null;
        String scenarioIdStr = props.getProperty("scenarioid");
        if (scenarioIdStr == null || scenarioIdStr.isEmpty()) {
            scenarioIdStr = props.getProperty("scenarioId");
        }
        if (scenarioIdStr != null && !scenarioIdStr.isEmpty()) {
            try {
                targetScenarioId = Integer.parseInt(scenarioIdStr);
            } catch (NumberFormatException e) {
                System.err.println("Invalid scenarioid format: " + scenarioIdStr);
                return;
            }
        }

        List<ScenarioCase> allCases;
        try {
            allCases = fetchScenarioCases();
        } catch (Exception e) {
            System.err.println("Failed to fetch scenario cases: " + e.getMessage());
            throw e;
        }

        String caseIdStr = props.getProperty("caseid");
        if (caseIdStr != null && !caseIdStr.isEmpty()) {
            try {
                int targetCaseId = Integer.parseInt(caseIdStr);
                allCases = allCases.stream()
                    .filter(c -> c.id == targetCaseId)
                    .toList();
                if (allCases.isEmpty()) {
                    System.err.println("No case found with ID: " + targetCaseId);
                    return;
                }
                System.out.println("Running tests for case ID: " + targetCaseId);
            } catch (NumberFormatException e) {
                System.err.println("Invalid caseid format: " + caseIdStr);
                return;
            }
        }

        if (targetScenarioId != null) {
            int scenarioId = targetScenarioId;
            allCases = allCases.stream()
                .filter(c -> c.scenario != null && c.scenario.id == scenarioId)
                .toList();
            if (allCases.isEmpty()) {
                System.err.println("No case found with scenario ID: " + targetScenarioId);
                return;
            }
            System.out.println("Running tests for scenario ID: " + targetScenarioId);
        }

        List<ScenarioCase> testCases = allCases.stream()
                .filter(c -> c.isActive && c.isTestCase)
                .toList();

        if (testCases.isEmpty()) {
            System.out.println("No active test cases found.");
            return;
        }

        int totalTests = 0;
        for (ScenarioCase testCase : testCases) {
            if (testCase.expectations != null) {
                totalTests += testCase.expectations.size();
            }
        }

        System.out.println("Running " + totalTests + " tests from " + testCases.size() + " scenarios...\n");

        int currentTest = 0;
        int passedTests = 0;
        int failedTests = 0;
        List<FailedTest> failedTestDetails = new ArrayList<>();

        KataBrain brain = new KataBrain(props);
        try {
            for (ScenarioCase testCase : testCases) {
                if (testCase.expectations == null || testCase.expectations.isEmpty()) {
                    continue;
                }

                System.out.println(YELLOW + "Case: " + testCase.id +
                                 (testCase.description != null ? " - " + testCase.description : "") + RESET);
                System.out.println(YELLOW + "Path: " + testCase.path + RESET);

                for (ScenarioCase.Expectation expectation : testCase.expectations) {
                    currentTest++;

                    // Show progress bar
                    int progress = (currentTest * 100) / totalTests;
                    int barLength = 30;
                    int filled = (progress * barLength) / 100;
                    StringBuilder progressBar = new StringBuilder(YELLOW + "[");
                    for (int i = 0; i < barLength; i++) {
                        progressBar.append(i < filled ? "=" : " ");
                    }
                    progressBar.append("] ").append(progress).append("%" + RESET);
                    System.out.println(YELLOW + "\rTest " + currentTest + "/" + totalTests + " " + RESET + progressBar);

                    // Run analysis
                    AnalysisRequest request = new AnalysisRequest();
                    request.scenario = new AnalysisRequest.Scenario();
                    request.scenario.id = testCase.scenario.id;
                    request.scenario.sgf = testCase.scenario.sgf;
                    request.scenario.metadata = testCase.scenario.metadata;
                    request.path = expectation.path;
                    request.difficulty = testCase.difficulty;

                    AnalysisResult[] results;
                    try {
                        Analysis analysis = new Analysis(props, brain);
                        results = analysis.analyze(request);
                    } catch (Exception e) {
                        System.err.println("\n  Failed to analyze: " + e.getMessage());
                        failedTests++;
                        continue;
                    }

                if (results == null || results.length == 0) {
                    System.err.println("\n  No analysis results returned");
                    failedTests++;
                    continue;
                }

                // Find root node (path="") for totalLoss calculation
                AnalysisResult rootResult = null;
                for (AnalysisResult r : results) {
                    if (r.path != null && r.path.isEmpty()) {
                        rootResult = r;
                        break;
                    }
                }

                AnalysisResult result = null;
                for (AnalysisResult r : results) {
                    if (r.path != null && r.path.equals(expectation.path)) {
                        result = r;
                        break;
                    }
                }

                if (result == null) {
                    System.err.println("\n  No result found for path: " + expectation.path);
                    failedTests++;
                    continue;
                }

                ScenarioCase.Tolerances tol = (expectation.tolerances != null) ? expectation.tolerances : testCase.tolerances;
                if (tol == null) {
                    System.err.println("\n  Warning: No tolerances specified for test, using defaults");
                    tol = new ScenarioCase.Tolerances();
                    tol.endness = DEFAULT_ENDNESS_TOLERANCE;
                    tol.urgency = DEFAULT_URGENCY_TOLERANCE;
                    tol.totalLoss = DEFAULT_TOTAL_LOSS_TOLERANCE;
                }

                // Validate expectations
                boolean passed = true;
                List<String> failures = new ArrayList<>();

                if (expectation.expected.endness != null && result.endness != null) {
                    boolean actualEndness = result.endness > 0.0;
                    if (actualEndness != expectation.expected.endness) {
                        passed = false;
                        failures.add("endness: expected " + expectation.expected.endness +
                                   ", got " + actualEndness + " (raw: " + df.format(result.endness) + ")");
                    }
                }

                if (expectation.expected.urgency != null && result.urgency != null) {
                    double tolerance = tol.urgency != null ? tol.urgency : DEFAULT_URGENCY_TOLERANCE;
                    double diff = Math.abs(result.urgency - expectation.expected.urgency);
                    if (diff > tolerance) {
                        passed = false;
                        failures.add("urgency: expected " + df.format(expectation.expected.urgency) + 
                                   ", got " + df.format(result.urgency) + " (diff: " + df.format(diff) + ", tolerance: " + df.format(tolerance) + ")");
                    }
                }

                if (expectation.expected.totalLoss != null && result.score != null && rootResult != null && rootResult.score != null) {
                    double tolerance = tol.totalLoss != null ? tol.totalLoss : DEFAULT_TOTAL_LOSS_TOLERANCE;
                    double totalLoss = rootResult.score - result.score;
                    double diff = Math.abs(totalLoss - expectation.expected.totalLoss);
                    if (diff > tolerance) {
                        passed = false;
                        failures.add("totalLoss: expected " + df.format(expectation.expected.totalLoss) + 
                                   ", got " + df.format(totalLoss) + " (diff: " + df.format(diff) + ", tolerance: " + df.format(tolerance) + ")");
                    }
                }

                if (passed) {
                    passedTests++;
                    System.out.println(GREEN + "Test passed - Case ID: " + testCase.id + RESET);
                    String url = buildResearchUrl(testCase.scenario.id, testCase.difficulty, expectation.path);
                    System.out.println(GREEN + "URL: " + url + RESET);
                } else {
                    failedTests++;
                    System.out.println(RED + "Test failed - Case ID: " + testCase.id + RESET);
                    String url = buildResearchUrl(testCase.scenario.id, testCase.difficulty, expectation.path);
                    System.out.println(RED + "URL: " + url + RESET);
                    failedTestDetails.add(new FailedTest(testCase.id, testCase.description, expectation.path, testCase.scenario.id, testCase.difficulty, new ArrayList<>(failures), tol, result.extraInfo));
                }
            }
        }

            System.out.println();

            System.out.println("\n======================");
            System.out.println(CYAN + "Test Results Summary" + RESET);
            System.out.println("======================");
            System.out.println("Total tests: " + totalTests);
            System.out.println(GREEN + "Passed: " + passedTests + " (" + (passedTests * 100 / totalTests) + "%)" + RESET);
            if (failedTests > 0) {
                System.out.println(RED + "Failed: " + failedTests + " (" + (failedTests * 100 / totalTests) + "%)" + RESET);
            }

            if (!failedTestDetails.isEmpty()) {
                System.out.println("\n======================");
                System.out.println(RED + "Failed Test Details" + RESET);
                System.out.println("======================");
                for (FailedTest failed : failedTestDetails) {
                    System.out.println("\nCase ID: " + failed.caseId +
                                     (failed.description != null ? " - " + failed.description : ""));
                    System.out.println("  Path: " + failed.path);
                    for (String failure : failed.failures) {
                        System.out.println("    " + YELLOW + "- " + failure + RESET);
                    }
                    // Build and display the research URL
                    String url = buildResearchUrl(failed.invasionId, failed.difficulty, failed.path);
                    System.out.println("  " + YELLOW + "URL: " + url + RESET);
                    // Display debug info if available
                    if (failed.extraInfo != null && !failed.extraInfo.isEmpty()) {
                        System.out.println("  Debug Info: " + failed.extraInfo);
                    }
                }
            }
        } finally {
            // Always stop KataBrain to allow program to exit
            brain.stopKataBrain();
        }
    }

    /**
     * Build research URL for a given invasion ID, difficulty and path.
     *
     * @param invasionId The invasion ID
     * @param difficulty The difficulty level
     * @param path The path in A1 format (e.g., "A1,B2,C3")
     * @return The research URL
     */
    private String buildResearchUrl(int invasionId, String difficulty, String path) {
        String baseUrl = props.getProperty("baseurl", "https://staging.goproblems.com/");
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        // URL encode the path: replace commas with %2C
        String encodedPath = path != null ? path.replace(",", "%2C") : "";
        return baseUrl + "/invasions/" + invasionId + "/research?difficulty=" + difficulty + "&path=" + encodedPath;
    }

    private static final String invasion1 = "(;GM[1]FF[4]CA[UTF-8]AP[Drago:4.33]SZ[19]KM[9.5]AB[db][eb][nb][ob][hc][lc][qd][he][le][qe][ef][gf][if][pf][jg][lg][ch][eh][jh][kh][ph][pj][pk][ql][pm][qn][mo][oo][dp][gp][hp][ip][jp][np][op][dq][iq][kq][nq][pq][dr][or]AW[fb][hb][pb][cc][ec][fc][ic][jc][oc][qc][rc][dd][nd][pd][je][cf][jf][kf][lf][nf][kg][nh][nj][ok][ol][pl][mp][pp][qp][eq][gq][hq][jq][mq][er][ir][jr][kr][lr][nr][ms]PL[W])";
    private static final String invasion2 = """
            (;GM[1]
            FF[4]
            AP[Drago:4.33]
            CA[UTF-8]
            SZ[19]
            KM[7.5]
            AB[gb][ib][jb][fc][hc][ic][oc][pc][ed][fd][id][qd][de][je][ke][cf]
            [df][kf][qf][gg][hg][ig][kg][bh][ch][eh][kh][di][ei][fi][gi][ki]
            [gj][gk][hk][kk][il][jl][am][bm][gm][km][cn][en][gn][ao][bo][co]
            [qo][bp][gp][hp][cq][pq][ar][br][cr]
            AW[ea][ga][ha][db][fb][kb][dc][ec][jc][lc][nc][dd][gd][hd][jd][ld]
            [nd][od][be][ce][ee][ie][ff][gf][hf][if][jf][bg][jg][ih][jh][ci]
            [ji][cj][ej][hj][ek][ik][jk][bl][cl][cm][em][dn][fn][do][fo][cp]
            [dp][dq][dr][fr][hr][ir][bs][cs]
            PL[W]
            )
            """;
    private static final String invasion4 = """
(;GM[1]
FF[4]
AP[Drago:4.33]
CA[UTF-8]
SZ[19]
KM[6.5]
AB[nb][ob][oc][pd][me][of][qf][og][ph][qh][ri][rj][rk][jl][kl][ll]
[ml][hm][im][om][in][ln][nn][ho][io][oo][qo][ro][bp][cp][gp][np]
[pp][qp][dq][fq][gq][pq][br][cr][dr][er]
AW[mb][fc][mc][nc][dd][nd][ne][nf][ng][nh][oh][oi][pi][nj][pj][jk]
[kk][lk][mk][hl][il][nl][ol][pl][ql][gm][qm][cn][gn][hn][pn][qn]
[rn][co][go][po][dp][ep][fp][bq][cq][eq]
PL[B]
)
            """;
    public record ScenTest(String sgf, String path, double score, double loss, String responseMove, String rank, boolean ends) {}

    public void runSuite() throws Exception {
        ScenTest[] tests = {
//                new ScenTest(invasion1, "B2", 6.5, 9.0, "C5", "ai"),
                new ScenTest(invasion1, "C7,C5,C10,D9,C9,D7,D8,E8,C8,E9,D6,E7,C6,E4,B12,B13,B11,C13,B5,B4,D5,C4,B6", 6.5, 9.0, "C5", "15k", true),
                new ScenTest(invasion1, "F5", 6.5, 9.0, "C5", "15k", false),
                new ScenTest(invasion2, "R8,R11,R6,Q6,S6", 6.5, 9.0, "C5", "15k", false),
//                new ScenTest(invasion4, "G8,J9,F8,F7,E7", 6.5, 9.0, "C5", "15k", false),
        };

        KataBrain brain = new KataBrain(props);

        // iterate through tests
        int cnt = 0, failures = 0;
        for (ScenTest test : tests) {
            // print current test number and path; concatenate properly
            System.out.println("Running scenario \u001B[32mtest\u001B[0m #" + cnt + ", path: " + test.path);

            Analysis analysis = new Analysis(props, brain);
            try {
                var req = new AnalysisRequest();
                req.scenario = new AnalysisRequest.Scenario();
                req.scenario.sgf = test.sgf;
                req.difficulty = test.rank;
                req.path = test.path;

                AnalysisResult[] results = analysis.analyze(req);
                AnalysisResult result = null;
                // find result for test path
                for (AnalysisResult r : results) {
                    if (r.path != null && r.path.equals(test.path)) {
                        result = r;
                        break;
                    }
                }
                if (result == null) {
                    System.out.println("❌❌❌ No result found for path: " + test.path);
                    continue;
                }
                if (result.difficulty.equals(test.rank)) {
                    System.out.println("Test passed ✔ for expected rank: " + test.rank);
                } else {
                    System.out.println("Test failed for expected rank: " + test.rank +
                            ", got: " + result.difficulty);
                }

                boolean ends = result.endness > 0.0;
                if (ends == test.ends) {
                    System.out.println("✅ for endness: " + result.endness);
                } else {
                    System.out.println("❌ for endness: " + result.endness);
                    failures++;
                }
//                double resultScore = result.score;
//                if (Math.abs(resultScore - test.score) < SCORE_RANGE) {
//                    System.out.println("Test passed for expected score: " + df.format(test.score));
//                } else {
//                    System.out.println("Test failed for expected score: " + df.format(test.score) +
//                            ", got: " + df.format(resultScore));
//                }
//
//                double resultLoss = result.loss;
//                if (Math.abs(resultLoss - test.loss) < LOSS_RANGE) {
//                    System.out.println("Test passed for expected loss: " + df.format(test.loss));
//                } else {
//                    System.out.println("Test failed for expected loss: " + df.format(test.loss) +
//                            ", got: " + df.format(resultLoss));
//                }
//
//                // verify response move if in test
//                if (test.responseMove != null && !test.responseMove.isEmpty()) {
//                    AnalysisResult response = results[1]; // response move
//                    String move = response.path.substring(response.path.lastIndexOf(',') + 1);
//                    if (move.equals(test.responseMove)) {
//                        System.out.println("Test passed for expected response move: " + move);
//                    } else {
//                        System.out.println("Test failed for expected response move: " + test.responseMove +
//                                ", got: " + move);
//                    }
//                }
            } catch (Exception e) {
                System.out.println("Exception during test for expected score: " + df.format(test.score));
                e.printStackTrace();
            }
            cnt++;
        }

        if (failures > 0) {
            System.out.println("Total failures: " + failures + " out of " + tests.length + " tests.");
        } else {
            System.out.println("All " + cnt + " tests passed! ✅");
        }

        brain.stopKataBrain();
    }
}
