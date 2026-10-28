package autoprob.validate;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import autoprob.ApiClient;

// goproblems.com's api, as the crawl and the suggestions use it (gp2's controllers):
// - GET  api/v2/problems/{id}: a problem with its sgf, genre, isStandard, alive, sandbox and
//   suggestionsOpenCount; reading it changes nothing on the site
// - POST api/v2/problems/paginated: the problems, 10 to 100 at a time, sortable by id or
//   createdAt (sorting by tries gives a server error)
// - GET  api/v2/suggested_problem_edits/?criteria[problemId]=: a problem's suggestions; the
//   path's {id} is a suggestion's own
// - POST api/v2/suggested_problem_edits: a new suggestion
// every call waits api.delay_ms (200) first, to go easy on the site, and one that fails for
// the network or a server error is tried twice more
public class GoproblemsSite {
    private final Properties props;
    private final ApiClient api = new ApiClient();
    private final long delay;

    public GoproblemsSite(Properties props) {
        this.props = new Properties();
        this.props.putAll(props);
        this.props.putIfAbsent("api.problem.details", "api/v2/problems/{id}");
        this.props.putIfAbsent("api.problems.paginated", "api/v2/problems/paginated");
        this.props.putIfAbsent("api.suggestions", "api/v2/suggested_problem_edits/");
        this.props.putIfAbsent("api.suggestion", "api/v2/suggested_problem_edits/{id}");
        this.props.putIfAbsent("api.suggestion.create", "api/v2/suggested_problem_edits");
        this.delay = Long.parseLong(props.getProperty("api.delay_ms", "200"));
    }

    // the problem, or null when the site has none at the id
    public JsonObject problem(int id) throws Exception {
        return call("GET", "api.problem.details", Map.of("id", String.valueOf(id)), null, null, true);
    }

    // the highest problem id the site has
    public int newestId() throws Exception {
        JsonObject body = new JsonObject();
        JsonObject criteria = new JsonObject();
        criteria.addProperty("limit", 10);
        criteria.addProperty("offset", 0);
        criteria.addProperty("sortBy", "id");
        criteria.addProperty("sortDirection", "desc");
        body.add("criteria", criteria);
        JsonArray entries = call("POST", "api.problems.paginated", null, null, body.toString(), false).getAsJsonArray("entries");
        if (entries.isEmpty()) throw new IOException("the site lists no problems");
        return entries.get(0).getAsJsonObject().get("id").getAsInt();
    }

    // the problem's suggestions, any status, newest first
    public JsonArray suggestions(int problemId) throws Exception {
        return call("GET", "api.suggestions", null, "?criteria%5BproblemId%5D=" + problemId + "&criteria%5Blimit%5D=100",
                null, false).getAsJsonArray("entries");
    }

    // a suggestion with its sgfs, or null when there is none
    public JsonObject suggestion(int id) throws Exception {
        return call("GET", "api.suggestion", Map.of("id", String.valueOf(id)), null, null, true);
    }

    // sent once, never again on a failure: the site may have made it all the same, and a second
    // one would be a duplicate
    public JsonObject create(JsonObject suggestion) throws Exception {
        return call("POST", "api.suggestion.create", null, null, suggestion.toString(), false, 1);
    }

    private JsonObject call(String method, String endpoint, Map<String, String> params, String query, String body,
                            boolean missingIsNull) throws Exception {
        return call(method, endpoint, params, query, body, missingIsNull, 3);
    }

    private JsonObject call(String method, String endpoint, Map<String, String> params, String query, String body,
                            boolean missingIsNull, int attempts) throws Exception {
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (attempt > 1) Thread.sleep(2000L * (attempt - 1));
            if (delay > 0) Thread.sleep(delay);
            ApiClient.ApiResponse<JsonObject> r;
            try {
                r = api.makeApiRequest(endpoint, method, params, query, body, JsonObject.class, props);
            } catch (IOException e) {
                last = e;
                continue;
            }
            if (r.isSuccess()) return r.getData();
            int code = r.getStatusCode();
            if (missingIsNull && (code == 404 || code == 403 || code == 410)) return null;
            last = new IOException(endpoint + ": http " + code + " " + r.getErrorMessage());
            // a request the site turns down will be turned down again
            if (code < 500) throw last;
        }
        throw last;
    }
}
