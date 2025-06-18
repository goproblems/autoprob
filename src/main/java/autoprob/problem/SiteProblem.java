package autoprob.problem;

import autoprob.ApiClient;
import autoprob.api.Problem;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.HashMap;
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
        } else {
            System.out.println("\n=== Problem Request Failed ===");
            System.out.println("Error Code: " + response.getStatusCode());
            System.out.println("Error Message: " + response.getErrorMessage());
        }
    }
}
