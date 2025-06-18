package autoprob;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class ApiClient {

    private Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public class ApiResponse<T> {
        private int statusCode;
        private T data;
        private String rawContent;
        private String errorMessage;
        
        public ApiResponse(int statusCode, T data, String rawContent) {
            this.statusCode = statusCode;
            this.data = data;
            this.rawContent = rawContent;
        }
        
        public ApiResponse(int statusCode, String errorMessage, String rawContent) {
            this.statusCode = statusCode;
            this.errorMessage = errorMessage;
            this.rawContent = rawContent;
        }
        
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }
        
        public int getStatusCode() {
            return statusCode;
        }
        
        public T getData() {
            return data;
        }
        
        public String getRawContent() {
            return rawContent;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
    }
    
    public String buildUrl(String endpointKey, Map<String, String> pathParams, String queryString, Properties props) {
        String baseUrl = props.getProperty("baseurl");
        String endpoint = props.getProperty(endpointKey);
        
        if (baseUrl == null || endpoint == null) {
            throw new RuntimeException("Missing required properties: baseurl or " + endpointKey);
        }
        
        if (pathParams != null) {
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                endpoint = endpoint.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        
        String url = baseUrl + (endpoint.startsWith("/") ? endpoint.substring(1) : endpoint);
        
        if (queryString != null && !queryString.isEmpty()) {
            url += queryString;
        }
        
        return url;
    }
    
    public <T> ApiResponse<T> makeApiRequest(String endpointKey, String method, 
            Map<String, String> pathParams, String queryString, String requestBody, 
            Class<T> responseType, Properties props) throws Exception {
        boolean debug = Boolean.parseBoolean(props.getProperty("debug", "false"));
        boolean printCurl = Boolean.parseBoolean(props.getProperty("curl", "false"));

        String apiKey = props.getProperty("apikey");
        if (apiKey == null) {
            throw new RuntimeException("Missing required property: apikey");
        }
        
        String urlString = buildUrl(endpointKey, pathParams, queryString, props);
        
        System.out.println("Calling API URL: " + urlString);
        
        if (printCurl) {
            StringBuilder curlCmd = new StringBuilder("curl -X " + method + " \\\n");
            curlCmd.append("  \"" + urlString + "\" \\\n");
            curlCmd.append("  -H \"X-Api-Key: " + apiKey + "\" \\\n");
            curlCmd.append("  -H \"Accept: application/json\" \\\n");
            
            if ("POST".equals(method) && requestBody != null && !requestBody.isEmpty()) {
                curlCmd.append("  -H \"Content-Type: application/json\" \\\n");
                curlCmd.append("  -d '" + requestBody + "'");
            }
            
            System.out.println("\nEquivalent curl command:");
            System.out.println(curlCmd.toString());
            System.out.println();
        }
        
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setRequestProperty("X-Api-Key", apiKey);
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        
        if ("POST".equals(method)) {
            if (debug) {
                System.out.println("POST Request body: " + requestBody);
            }
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            
            if (requestBody != null && !requestBody.isEmpty()) {
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = requestBody.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }
            } else {
                connection.getOutputStream().close();
            }
        }
        
        int responseCode = connection.getResponseCode();
        if (Boolean.parseBoolean(props.getProperty("debug", "false"))) {
            System.out.println("Response code: " + responseCode);
        }
        
        String responseContent = readResponseContent(connection, responseCode);
        if (debug) {
            System.out.println("Request response: " + responseContent);
        }

        if (responseCode >= 200 && responseCode < 300) {
            T parsedResponse = gson.fromJson(responseContent, responseType);
            return new ApiResponse<>(responseCode, parsedResponse, responseContent);
        } else {
            String errorMessage = parseErrorMessage(responseContent);
            return new ApiResponse<>(responseCode, errorMessage, responseContent);
        }
    }
    
    public <T> ApiResponse<T> makeGetRequest(String endpointKey, Map<String, String> pathParams, 
            String queryString, Class<T> responseType, Properties props) throws Exception {
        return makeApiRequest(endpointKey, "GET", pathParams, queryString, null, responseType, props);
    }
    
    public <T> ApiResponse<T> makePostRequest(String endpointKey, Map<String, String> pathParams, 
            String requestBody, Class<T> responseType, Properties props) throws Exception {
        return makeApiRequest(endpointKey, "POST", pathParams, null, requestBody, responseType, props);
    }
    
    public String readResponseContent(HttpURLConnection connection, int responseCode) throws Exception {
        StringBuilder responseContent = new StringBuilder();
        try (BufferedReader reader = (responseCode >= 200 && responseCode < 300) ? 
             new BufferedReader(new InputStreamReader(connection.getInputStream())) : 
             new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
            
            String line;
            while ((line = reader.readLine()) != null) {
                responseContent.append(line);
            }
        }
        return responseContent.toString();
    }
    
    public String parseErrorMessage(String errorContent) {
        try {
            JsonElement jsonElement = new JsonParser().parse(errorContent);
            JsonObject errorJson = jsonElement.getAsJsonObject();
            if (errorJson.has("message")) {
                return errorJson.get("message").getAsString();
            }
        } catch (Exception e) {
        }
        return errorContent;
    }
}
