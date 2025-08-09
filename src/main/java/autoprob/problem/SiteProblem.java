package autoprob.problem;

import autoprob.ApiClient;
import autoprob.api.Attempt;
import autoprob.api.AttemptListResponse;
import autoprob.api.Problem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class SiteProblem {
    public void execute(Properties props) throws Exception {
        String problemId = props.getProperty("id");
        if (problemId == null) {
            throw new RuntimeException("Missing required parameter: id. Usage: cmd=problem id=123");
        }

        Map<String, String> pathParams = new HashMap<>();
        pathParams.put("id", problemId);

        ApiClient apiClient = new ApiClient();
        ApiClient.ApiResponse<Problem> response = apiClient.makeGetRequest(
                "api.problem.details", pathParams, null, Problem.class, props);

        if (response.isSuccess()) {
            Problem problem = response.getData();

            // Display problem details
            System.out.println("\n=== Problem Details ===");
            System.out.println(problem.toString());

            // Display problem image URL for user reference
            if (problem.imageUrl != null && !problem.imageUrl.isEmpty()) {
                System.out.println("Image URL: " + problem.imageUrl);
            }

            // Display raw JSON if in debug mode
            if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                System.out.println("\nRaw API Response:");
                Gson gson = new GsonBuilder().setPrettyPrinting().create();
                System.out.println(gson.toJson(problem));
            }

            // Load and display attempts
            int maxAttempts = Integer.parseInt(props.getProperty("attempts.limit", "10"));
            AttemptListResponse attemptsResponse = loadAttempts(props, problemId, maxAttempts);
            if (attemptsResponse != null) {
                System.out.println("\n" + attemptsResponse.toString());
            }
        } else {
            System.out.println("\n=== Problem Request Failed ===");
            System.out.println("Error Code: " + response.getStatusCode());
            System.out.println("Error Message: " + response.getErrorMessage());
        }
    }

    private AttemptListResponse loadAttempts(Properties props, String problemId, int maxAttempts) {
        try {
            List<Attempt> allAttempts = new ArrayList<>();
            int offset = 0;
            int pageSize = Math.min(maxAttempts, 50); // API might have limits, so use reasonable page size
            int totalCount = 0;
            boolean hasMore = true;
            
            ApiClient apiClient = new ApiClient();
            
            // Load attempts with pagination until we have enough or no more available
            while (allAttempts.size() < maxAttempts && hasMore) {
                int requestLimit = Math.min(pageSize, maxAttempts - allAttempts.size());
                
                // Build query string with pagination parameters
                String queryString = String.format("?limit=%d&offset=%d&sort_direction=desc&sort_by=id&problem_id=%s",
                    requestLimit, offset, problemId);
                
                if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                    System.out.println("Loading attempts with query: " + queryString);
                }
                
                // Make API request for this page
                ApiClient.ApiResponse<AttemptListResponse> response = apiClient.makeGetRequest(
                        "api.user.attempts", null, queryString, AttemptListResponse.class, props);
                
                if (response.isSuccess()) {
                    AttemptListResponse pageResponse = response.getData();
                    if (pageResponse != null && pageResponse.items != null) {
                        allAttempts.addAll(pageResponse.items);
                        totalCount = pageResponse.totalRecords;
                        hasMore = pageResponse.items.size() == requestLimit && (offset + pageResponse.items.size()) < totalCount;
                        offset += pageResponse.items.size();
                        
                        if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                            System.out.println("Loaded " + pageResponse.items.size() + " attempts, total so far: " + allAttempts.size());
                        }
                    } else {
                        hasMore = false;
                    }
                } else {
                    System.out.println("Failed to load attempts: " + response.getErrorMessage());
                    if (allAttempts.isEmpty()) {
                        return null;
                    }
                    break;
                }
            }
            
            // Create final response with all collected attempts
            AttemptListResponse finalResponse = new AttemptListResponse(allAttempts, totalCount, allAttempts.size(), 0);
            finalResponse.hasMore = allAttempts.size() < totalCount;
            
            return finalResponse;
            
        } catch (Exception e) {
            System.out.println("Error loading attempts: " + e.getMessage());
            if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                e.printStackTrace();
            }
            return null;
        }
    }

    /* how the PHP code does elo calculation:
        public const K_VAL = 32;
    public const K_VAL_PROBLEM = 140;
    public const K_FADE = 80;
    public const K_FADE_PROBLEM = 10;

    $newProblemElo = $this->eloCalculator->calculateProblemElo(
            $context->getProblemElo(),
            $context->getUserElo(),
            $context->getTriesCount(),
            $solved,
            EloCalculatorInterface::K_VAL_PROBLEM,
            EloCalculatorInterface::K_FADE_PROBLEM
        );

    public function calculateProblemElo(
        float $problemElo,
        float $userElo,
        int $triesCount,
        bool $solved,
        int $kVal,
        int $kFade
    ): float {
        $kProblem  = $kVal * $kFade / ($kFade + sqrt(1 + $triesCount));

        return $problemElo
            + ($solved ? -1 : 1) * $kProblem
            * (1.0 - $this->calculateUserExperience($problemElo, $userElo, $solved));
    }
     */
}
