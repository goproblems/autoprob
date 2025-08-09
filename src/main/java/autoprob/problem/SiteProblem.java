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
    // Elo calculation constants from PHP code
    private static final int K_VAL = 32;
    private static final int K_VAL_PROBLEM = 140;
    private static final int K_FADE = 80;
    private static final int K_FADE_PROBLEM = 10;
    
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
            String attemptsLimitStr = props.getProperty("attempts.limit", "100");
            int maxAttempts = Integer.parseInt(attemptsLimitStr);
            if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                System.out.println("attempts.limit property value: '" + attemptsLimitStr + "' -> maxAttempts: " + maxAttempts);
            }
            AttemptListResponse attemptsResponse = loadAttempts(props, problemId, maxAttempts);
            if (attemptsResponse != null) {
                System.out.println("\n" + attemptsResponse.toString());
                
                // Simulate Elo calculation
                simulateEloCalculation(problem, attemptsResponse);
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
            int pageSize = 50; // Use a fixed page size for API requests
            int totalCount = 0;
            boolean hasMore = true;
            
            ApiClient apiClient = new ApiClient();
            
            if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                System.out.println("Starting to load attempts, maxAttempts=" + maxAttempts + ", pageSize=" + pageSize);
            }
            
            // Load attempts with pagination until we have enough or no more available
            while (allAttempts.size() < maxAttempts && hasMore) {
                int requestLimit = Math.min(pageSize, maxAttempts - allAttempts.size());
                
                // Build query string with pagination parameters
                String queryString = String.format("?limit=%d&offset=%d&sort_direction=desc&sort_by=id&problem_id=%s",
                    requestLimit, offset, problemId);
                
                if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                    System.out.println("Loading attempts with query: " + queryString + 
                                     " (requestLimit=" + requestLimit + ", offset=" + offset + ")");
                }
                
                // Make API request for this page
                ApiClient.ApiResponse<AttemptListResponse> response = apiClient.makeGetRequest(
                        "api.user.attempts", null, queryString, AttemptListResponse.class, props);
                
                if (response.isSuccess()) {
                    AttemptListResponse pageResponse = response.getData();
                    if (pageResponse != null && pageResponse.items != null) {
                        allAttempts.addAll(pageResponse.items);
                        totalCount = pageResponse.totalRecords;
                        offset += pageResponse.items.size();
                        
                        // Continue if we haven't loaded all available attempts and haven't reached our limit
                        hasMore = allAttempts.size() < totalCount && allAttempts.size() < maxAttempts;
                        
                        if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
                            System.out.println("Loaded " + pageResponse.items.size() + " attempts, total so far: " + allAttempts.size() + 
                                             ", totalRecords from API: " + totalCount + ", offset now: " + offset + ", hasMore: " + hasMore);
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
       public const K_USER_EXPERIENCE_DIVIDER = 400.0;

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

    private function calculateUserExperience(float $problemElo, float $userElo, bool $solved): float
    {
        $result = 1.0 / (1.0 + 10 ** (($problemElo - $userElo) / EloCalculatorInterface::K_USER_EXPERIENCE_DIVIDER));
        if (!$solved) {
            $result = 1.0 - $result;
        }

        return $result;
    }
     */
    
    /**
     * Calculate user experience based on the PHP implementation
     * From the PHP code:
     * $result = 1.0 / (1.0 + 10 ** (($problemElo - $userElo) / EloCalculatorInterface::K_USER_EXPERIENCE_DIVIDER));
     * if (!$solved) { $result = 1.0 - $result; }
     */
    private double calculateUserExperience(double problemElo, double userElo, boolean solved) {
        // K_USER_EXPERIENCE_DIVIDER = 400.0 from the PHP constants
        double K_USER_EXPERIENCE_DIVIDER = 400.0;
        
        double result = 1.0 / (1.0 + Math.pow(10, (problemElo - userElo) / K_USER_EXPERIENCE_DIVIDER));
        
        if (!solved) {
            result = 1.0 - result;
        }
        
        return result;
    }
    
    /**
     * Calculate new problem Elo based on attempt result
     * Based on the PHP implementation
     */
    private double calculateProblemElo(double problemElo, double userElo, int triesCount, 
                                      boolean solved, int kVal, int kFade) {
        double kProblem = kVal * kFade / (kFade + Math.sqrt(1 + triesCount));
        
        double newElo = problemElo + 
            (solved ? -1 : 1) * kProblem * 
            (1.0 - calculateUserExperience(problemElo, userElo, solved));
            
        return newElo;
    }
    
    /**
     * Simulate Elo calculation for problem based on attempts
     */
    private void simulateEloCalculation(Problem problem, AttemptListResponse attemptsResponse) {
        // Use suggestedElo if available, otherwise default to 1200
        double startingElo = (problem.suggestedElo != null) ? problem.suggestedElo : 1200.0;
        String startingRank = EloRankCalculator.calculateEloShortLevelName(startingElo);
        
        System.out.println("\n=== Elo Simulation ===");
        System.out.println(String.format("Starting with initial Elo: %.1f (%s)%s", 
            startingElo, startingRank,
            problem.suggestedElo != null ? " [using suggested Elo]" : " [default]"));
        System.out.println("Processing attempts in chronological order (oldest first)");
        
        // Print table header
        System.out.println("\n" + String.format("%-12s %-10s %-12s %-12s %-12s %-12s %-10s", 
            "Attempt #", "Result", "User Rank", "User Elo", "Problem Elo", "New Elo", "New Rank"));
        System.out.println("-".repeat(90));
        
        double currentElo = startingElo;
        int attemptCount = 0;
        
        // Process attempts in chronological order (oldest first)
        // Since API returns newest first, we need to reverse
        List<Attempt> attempts = new ArrayList<>(attemptsResponse.items);
        java.util.Collections.reverse(attempts);
        
        for (Attempt attempt : attempts) {
            attemptCount++;
            
            // Get user elo, skip if not available
            if (attempt.user == null || attempt.user.elo == null) {
                continue;
            }
            
            double userElo = attempt.user.elo;
            String userRank = "N/A";
            if (attempt.user.rank != null) {
                userRank = attempt.user.rank.value + attempt.user.rank.unit;
            }
            
            // Calculate new Elo
            double newElo = calculateProblemElo(currentElo, userElo, attemptCount, 
                                               attempt.solved, K_VAL_PROBLEM, K_FADE_PROBLEM);
            
            // Calculate new rank from new Elo
            String newRank = EloRankCalculator.calculateEloShortLevelName(newElo);
            
            // Print row
            System.out.println(String.format("%-12s %-10s %-12s %-12.1f %-12.1f %-12.1f %-10s",
                "#" + attempt.id,
                attempt.solved ? "SOLVED" : "FAILED",
                userRank,
                userElo,
                currentElo,
                newElo,
                newRank));
            
            currentElo = newElo;
        }
        
        System.out.println("-".repeat(90));
        System.out.println(String.format("Final simulated Elo: %.1f (%s)", currentElo, 
                                        EloRankCalculator.calculateEloShortLevelName(currentElo)));
    }
}
