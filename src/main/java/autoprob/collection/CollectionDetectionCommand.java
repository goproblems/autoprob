package autoprob.collection;

import autoprob.api.Problem;
import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.parse.Parser;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Runs a named collection detector against GoProblems API problem SGFs. */
public final class CollectionDetectionCommand {
    private static final List<CollectionDetector> DETECTORS = List.of(
            new CenterTubesDetector(),
            SacrificeForLibertyDetector.loneStone(),
            SacrificeForLibertyDetector.addedStone());
    /** A suggestion's comment is cut to this length. */
    private static final int MAX_COMMENT = 480;

    private final Properties props;
    private final CollectionDetector detector;
    private final GoProblemsApi api;
    /** Offers the matches to the site for its collection, or null when suggest=true was not given. */
    private final Suggester suggester;

    public CollectionDetectionCommand(Properties props) {
        this.props = props;
        this.detector = createDetector(required(props, "collection"));
        this.api = new GoProblemsApi(props);
        this.suggester = Boolean.parseBoolean(props.getProperty("suggest", "false")) ? new Suggester() : null;
    }

    public void run() throws Exception {
        if (suggester != null) {
            suggester.load();
        }
        String idValue = props.getProperty("id");
        String startValue = props.getProperty("startid");
        String countValue = props.getProperty("count");
        String siteCollectionValue = props.getProperty("incollection");

        boolean range = startValue != null || countValue != null;
        if ((idValue != null ? 1 : 0) + (range ? 1 : 0) + (siteCollectionValue != null ? 1 : 0) > 1) {
            throw new IllegalArgumentException("Use only one of id, startid with count, or incollection");
        }

        if (idValue != null) {
            testOne(Integer.parseInt(idValue), true);
            return;
        }

        if (siteCollectionValue != null) {
            scanSiteCollection(Integer.parseInt(siteCollectionValue));
            return;
        }

        if (startValue != null || countValue != null) {
            if (startValue == null || countValue == null) {
                throw new IllegalArgumentException("Range mode requires both startid and count");
            }
            int startId = Integer.parseInt(startValue);
            int count = Integer.parseInt(countValue);
            if (startId < 1 || count < 1) {
                throw new IllegalArgumentException("startid and count must be positive");
            }
            scanRange(startId, count);
            return;
        }

        scanAllLiveProblems();
    }

    private void scanRange(int startId, int count) throws Exception {
        System.out.println("Scanning " + count + " problem IDs starting at " + startId
                + " for collection " + detector.name());
        ScanCounts counts = new ScanCounts();
        for (int id = startId; id < startId + count; id++) {
            inspect(id, false, counts);
        }
        printSummary(counts);
    }

    private void scanAllLiveProblems() throws Exception {
        System.out.println("Loading live problem IDs from GoProblems...");
        List<Integer> ids = api.fetchLiveProblemIds();
        System.out.println("Loaded " + ids.size() + " live problem IDs; checking starting positions...");
        scanIds(ids);
    }

    private void scanSiteCollection(int siteCollectionId) throws Exception {
        System.out.println("Loading the problem IDs of GoProblems collection " + siteCollectionId + "...");
        List<Integer> ids = api.fetchCollectionProblemIds(siteCollectionId);
        System.out.println("Loaded " + ids.size() + " problem IDs; checking them for collection "
                + detector.name() + "...");
        scanIds(ids);
    }

    private void scanIds(List<Integer> ids) {
        ScanCounts counts = new ScanCounts();
        for (int i = 0; i < ids.size(); i++) {
            inspect(ids.get(i), false, counts);
            if ((i + 1) % 100 == 0) {
                System.out.println("Checked " + (i + 1) + "/" + ids.size()
                        + " problems; matches=" + counts.matches);
            }
        }
        printSummary(counts);
    }

    private void testOne(int id, boolean showNonMatchBoard) throws Exception {
        Problem problem = api.fetchProblem(id);
        if (problem == null) {
            System.out.println("Problem " + id + " was not found");
            return;
        }

        Node startingPosition = parseStartingPosition(problem, id);
        CollectionDetector.MatchResult result = detector.detect(startingPosition);
        boolean live = Boolean.TRUE.equals(problem.alive);
        boolean matches = live && result.matches();
        System.out.println("Problem " + id + (matches ? " MATCHES " : " does not match ")
                + detector.name() + ": " + (live ? result.reason() : "problem is not live"));
        System.out.println(problemUrl(id));
        if (matches || showNonMatchBoard) {
            System.out.println(renderBoard(startingPosition.board));
        }
        if (matches && suggester != null) {
            suggester.offer(id, result.reason());
            System.out.println(suggester.summary());
        }
    }

    private void inspect(int id, boolean showNonMatchBoard, ScanCounts counts) {
        try {
            Problem problem = api.fetchProblem(id);
            if (problem == null) {
                counts.missing++;
                return;
            }
            if (!Boolean.TRUE.equals(problem.alive)) {
                counts.inactive++;
                return;
            }

            Node startingPosition = parseStartingPosition(problem, id);
            CollectionDetector.MatchResult result = detector.detect(startingPosition);
            counts.checked++;
            if (result.matches()) {
                counts.matches++;
                printMatch(id, startingPosition.board, result.reason());
                if (suggester != null) {
                    suggester.offer(id, result.reason());
                }
            } else if (showNonMatchBoard) {
                System.out.println("Problem " + id + " does not match: " + result.reason());
                System.out.println(renderBoard(startingPosition.board));
            }
        } catch (SuggestionFailed failure) {
            // not a problem that could not be checked: the site refused, and the run stops there
            throw failure;
        } catch (Exception exception) {
            counts.errors++;
            System.err.println("Could not check problem " + id + ": " + exception.getMessage());
        }
    }

    private Node parseStartingPosition(Problem problem, int id) throws Exception {
        if (problem.sgf == null || problem.sgf.isBlank()) {
            throw new IllegalArgumentException("problem " + id + " has no SGF");
        }
        return new Parser().parse(problem.sgf);
    }

    private void printMatch(int id, Board board, String reason) {
        System.out.println();
        System.out.println("MATCH " + id + " - " + problemUrl(id));
        System.out.println(reason);
        System.out.println(renderBoard(board));
        System.out.println();
    }

    private String problemUrl(int id) {
        String baseUrl = props.getProperty("baseurl", "https://www.goproblems.com/");
        return URI.create(ensureTrailingSlash(baseUrl)).resolve("problems/" + id).toString();
    }

    static String renderBoard(Board board) {
        StringBuilder result = new StringBuilder();
        for (int y = 0; y < board.boardY; y++) {
            if (y > 0) {
                result.append('\n');
            }
            for (int x = 0; x < board.boardX; x++) {
                int stone = board.board[x][y].stone;
                result.append(stone == Intersection.BLACK ? 'X'
                        : stone == Intersection.WHITE ? 'O' : '.');
            }
        }
        return result.toString();
    }

    private void printSummary(ScanCounts counts) {
        System.out.println("Scan complete: checked=" + counts.checked + ", matches=" + counts.matches
                + ", inactive=" + counts.inactive + ", missing=" + counts.missing
                + ", errors=" + counts.errors);
        if (suggester != null) {
            System.out.println(suggester.summary());
        }
    }

    private CollectionDetector createDetector(String collectionName) {
        List<String> names = new ArrayList<>();
        for (CollectionDetector candidate : DETECTORS) {
            if (candidate.name().equalsIgnoreCase(collectionName)) {
                return candidate;
            }
            names.add(candidate.name());
        }
        throw new IllegalArgumentException("Unknown collection detector: " + collectionName
                + ". Available detectors: " + String.join(", ", names));
    }

    private static String required(Properties props, String name) {
        String value = props.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required parameter: " + name);
        }
        return value;
    }

    private static String ensureTrailingSlash(String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    /**
     * Offers matches to GoProblems as suggestions to add them to the detector's collection there
     * (suggest=true), which the problem's author or an editor of the site then approves or rejects.
     * A dry run unless suggest.post=true. A problem is offered once: not when the collection has
     * it already, and not when it was suggested for the collection before, whatever was decided.
     */
    private final class Suggester {
        private final int collection;
        private final boolean post;
        private final int limit;
        private Set<Integer> held;
        private Map<Integer, String> before;
        private int offered;
        private int leftAlone;

        private Suggester() {
            String given = props.getProperty("suggest.collection");
            this.collection = given == null || given.isBlank()
                    ? detector.siteCollection() : Integer.parseInt(given.trim());
            if (collection <= 0) {
                throw new IllegalArgumentException("Detector " + detector.name()
                        + " has no GoProblems collection of its own: give suggest.collection=<its id on the site>");
            }
            this.post = Boolean.parseBoolean(props.getProperty("suggest.post", "false"));
            this.limit = Integer.parseInt(props.getProperty("suggest.limit", Integer.toString(Integer.MAX_VALUE)));
            if (post && !api.hasKey()) {
                throw new IllegalArgumentException(
                        "suggest.post=true needs apikey: a suggestion is made as that key's user");
            }
        }

        private void load() throws Exception {
            held = new LinkedHashSet<>(api.fetchCollectionProblemIds(collection));
            before = api.fetchSuggestions(collection);
            System.out.println("Suggesting matches for GoProblems collection " + collection + ": it holds "
                    + held.size() + (held.size() == 1 ? " problem" : " problems") + ", and " + before.size()
                    + (before.size() == 1 ? " was" : " were") + " suggested for it before"
                    + (post ? "" : ". A dry run: suggest.post=true sends them"));
        }

        private void offer(int id, String reason) {
            if (held.contains(id)) {
                leftAlone++;
                System.out.println("Problem " + id + " is in collection " + collection + " already");
                return;
            }
            String earlier = before.get(id);
            if (earlier != null) {
                leftAlone++;
                System.out.println("Problem " + id + " was suggested for collection " + collection
                        + " before (" + earlier + ")");
                return;
            }
            if (offered >= limit) {
                leftAlone++;
                System.out.println("Problem " + id + " is not suggested: suggest.limit=" + limit + " is reached");
                return;
            }

            String comment = "Detected by autoprob (" + detector.name() + "): " + reason;
            if (comment.length() > MAX_COMMENT) {
                comment = comment.substring(0, MAX_COMMENT - 3) + "...";
            }
            offered++;
            if (!post) {
                System.out.println("Problem " + id + " would be suggested for collection " + collection
                        + ": " + comment);
                return;
            }
            try {
                String made = api.suggestAdding(id, collection, comment);
                before.put(id, "add open");
                System.out.println("Problem " + id + " suggested for collection " + collection
                        + (made == null ? "" : " as suggestion " + made) + ": " + comment);
            } catch (Exception exception) {
                throw new SuggestionFailed("Could not suggest problem " + id + " for collection " + collection
                        + ": " + exception.getMessage(), exception);
            }
        }

        private String summary() {
            return (post ? "Suggested: " : "Would suggest: ") + offered
                    + "; left alone (in the collection, suggested before, or over suggest.limit): " + leftAlone;
        }
    }

    /** The site refused a suggestion: nothing after it is offered. */
    private static final class SuggestionFailed extends RuntimeException {
        private SuggestionFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ScanCounts {
        private int checked;
        private int matches;
        private int inactive;
        private int missing;
        private int errors;
    }

    private static final class GoProblemsApi {
        private static final int LIST_PAGE_SIZE = 50;
        private static final int COLLECTION_PAGE_SIZE = 100;
        private static final int SUGGESTION_PAGE_SIZE = 100;

        private final Properties props;
        private final Gson gson = new Gson();
        private final long delayMillis;
        /** Where fetched problems are kept between runs, or null to ask the site every time. */
        private final Path cacheDir;
        private long lastRequestAt;

        private GoProblemsApi(Properties props) {
            this.props = props;
            this.delayMillis = Long.parseLong(props.getProperty("detectcollection.delay.ms", "500"));
            if (delayMillis < 0) {
                throw new IllegalArgumentException("detectcollection.delay.ms cannot be negative");
            }
            String cache = props.getProperty("detectcollection.cache");
            this.cacheDir = cache == null || cache.isBlank() ? null : Path.of(cache);
        }

        private Problem fetchProblem(int id) throws Exception {
            Path cached = cacheDir == null ? null : cacheDir.resolve(id + ".json");
            if (cached != null && Files.isRegularFile(cached)) {
                return gson.fromJson(Files.readString(cached, StandardCharsets.UTF_8), Problem.class);
            }

            String endpoint = props.getProperty("api.problem.details", "api/v2/problems/{id}")
                    .replace("{id}", Integer.toString(id));
            ApiResult result = get(endpoint);
            if (result.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return null;
            }
            requireSuccess(result, endpoint);
            Problem problem = gson.fromJson(result.body, Problem.class);
            if (cached != null && problem != null) {
                Files.createDirectories(cacheDir);
                Files.writeString(cached, result.body, StandardCharsets.UTF_8);
            }
            return problem;
        }

        private List<Integer> fetchCollectionProblemIds(int siteCollectionId) throws Exception {
            String endpoint = props.getProperty("api.collection.problems", "api/v2/collections/{id}/problems")
                    .replace("{id}", Integer.toString(siteCollectionId));
            int offset = 0;
            Set<Integer> ids = new LinkedHashSet<>();

            while (true) {
                String separator = endpoint.contains("?") ? "&" : "?";
                String pageEndpoint = endpoint + separator + "criteria%5Bcollection%5D=" + siteCollectionId
                        + "&criteria%5Blimit%5D=" + COLLECTION_PAGE_SIZE
                        + "&criteria%5Boffset%5D=" + offset;
                ApiResult result = get(pageEndpoint);
                requireSuccess(result, pageEndpoint);
                ProblemPage page = gson.fromJson(result.body, ProblemPage.class);
                if (page == null || page.entries == null || page.entries.isEmpty()) {
                    break;
                }
                int previousSize = ids.size();
                for (ProblemSummary summary : page.entries) {
                    ids.add(summary.id);
                }
                if (ids.size() == previousSize) {
                    throw new RuntimeException("Collection pagination returned a duplicate page at offset "
                            + offset);
                }
                offset += page.entries.size();
                if (page.entries.size() < COLLECTION_PAGE_SIZE) {
                    break;
                }
            }
            return new ArrayList<>(ids);
        }

        private List<Integer> fetchLiveProblemIds() throws Exception {
            String endpoint = props.getProperty("api.problem.list", "api/v2/problems");
            int offset = 0;
            Set<Integer> ids = new LinkedHashSet<>();

            while (true) {
                String separator = endpoint.contains("?") ? "&" : "?";
                String pageEndpoint = endpoint + separator + "offset=" + offset
                        + "&result_number=" + LIST_PAGE_SIZE
                        + "&sort_by=id&sort_direction=desc";
                ApiResult result = get(pageEndpoint);
                requireSuccess(result, pageEndpoint);
                List<ProblemSummary> page = gson.fromJson(result.body,
                        new TypeToken<List<ProblemSummary>>() { }.getType());
                if (page == null || page.isEmpty()) {
                    break;
                }
                int previousSize = ids.size();
                for (ProblemSummary summary : page) {
                    ids.add(summary.id);
                }
                if (ids.size() == previousSize) {
                    throw new RuntimeException("Problem-list pagination returned a duplicate page at offset "
                            + offset);
                }
                offset += page.size();
                if (page.size() < LIST_PAGE_SIZE) {
                    break;
                }
            }
            return new ArrayList<>(ids);
        }

        private boolean hasKey() {
            String apiKey = props.getProperty("apikey");
            return apiKey != null && !apiKey.isBlank();
        }

        /**
         * The problems suggested for the collection before, each with what was suggested and what
         * became of it: "add open", "add rejected", "remove approved".
         */
        private Map<Integer, String> fetchSuggestions(int siteCollectionId) throws Exception {
            String endpoint = props.getProperty("api.collection.suggestions", "api/v2/collection-suggestions");
            Map<Integer, String> found = new LinkedHashMap<>();
            // the site lists one status at a time; an open one comes last, to be the one kept
            for (String status : List.of("rejected", "approved", "open")) {
                int offset = 0;
                while (true) {
                    String pageEndpoint = endpoint + "?criteria%5BcollectionId%5D=" + siteCollectionId
                            + "&criteria%5Bstatus%5D=" + status
                            + "&criteria%5BsortBy%5D=id&criteria%5BsortDirection%5D=desc"
                            + "&criteria%5Blimit%5D=" + SUGGESTION_PAGE_SIZE
                            + "&criteria%5Boffset%5D=" + offset;
                    ApiResult result = get(pageEndpoint);
                    requireSuccess(result, pageEndpoint);
                    SuggestionPage page = gson.fromJson(result.body, SuggestionPage.class);
                    if (page == null || page.entries == null || page.entries.isEmpty()) {
                        break;
                    }
                    for (Suggestion suggestion : page.entries) {
                        if (suggestion.problem != null) {
                            found.put(suggestion.problem.id, suggestion.type + " " + status);
                        }
                    }
                    offset += page.entries.size();
                    if (page.entries.size() < SUGGESTION_PAGE_SIZE) {
                        break;
                    }
                }
            }
            return found;
        }

        /**
         * Suggests adding the problem to the collection, as the key's user. The new suggestion's
         * id, when the site's answer gives one.
         */
        private String suggestAdding(int problemId, int siteCollectionId, String comment) throws Exception {
            String endpoint = props.getProperty("api.collection.suggestions", "api/v2/collection-suggestions");
            JsonObject suggestion = new JsonObject();
            suggestion.addProperty("collectionId", siteCollectionId);
            suggestion.addProperty("type", "add");
            JsonArray suggestions = new JsonArray();
            suggestions.add(suggestion);
            JsonObject body = new JsonObject();
            body.addProperty("problemId", problemId);
            body.add("suggestions", suggestions);
            body.addProperty("comment", comment);

            ApiResult result = send("POST", endpoint, gson.toJson(body));
            if (result.statusCode < 200 || result.statusCode >= 300) {
                throw new RuntimeException("POST " + endpoint + " returned HTTP " + result.statusCode
                        + (result.body.isBlank() ? "" : ": " + result.body));
            }
            return suggestionId(result.body);
        }

        /** The id in the site's answer to a new suggestion, however it wraps it; null when it has none. */
        private String suggestionId(String body) {
            try {
                JsonElement answer = JsonParser.parseString(body);
                if (answer.isJsonObject() && answer.getAsJsonObject().has("entries")) {
                    answer = answer.getAsJsonObject().get("entries");
                }
                if (answer.isJsonArray() && answer.getAsJsonArray().size() > 0) {
                    answer = answer.getAsJsonArray().get(0);
                }
                if (answer.isJsonObject() && answer.getAsJsonObject().has("id")) {
                    return "#" + answer.getAsJsonObject().get("id").getAsString();
                }
            } catch (RuntimeException notJson) {
                // the suggestion was made all the same
            }
            return null;
        }

        private ApiResult get(String endpoint) throws Exception {
            return send("GET", endpoint, null);
        }

        private ApiResult send(String method, String endpoint, String payload) throws Exception {
            rateLimit();
            String baseUrl = props.getProperty("baseurl", "https://www.goproblems.com/");
            URL url = URI.create(ensureTrailingSlash(baseUrl)).resolve(endpoint).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "autoprob-collection-detector/1.0");
            String apiKey = props.getProperty("apikey");
            if (apiKey != null && !apiKey.isBlank()) {
                connection.setRequestProperty("X-Api-Key", apiKey.trim());
            }
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            if (payload != null) {
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload.getBytes(StandardCharsets.UTF_8));
                }
            }

            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode >= 200 && statusCode < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = read(stream);
            connection.disconnect();
            lastRequestAt = System.currentTimeMillis();
            return new ApiResult(statusCode, body);
        }

        private void rateLimit() throws InterruptedException {
            long remaining = delayMillis - (System.currentTimeMillis() - lastRequestAt);
            if (lastRequestAt != 0 && remaining > 0) {
                Thread.sleep(remaining);
            }
        }

        private String read(InputStream stream) throws Exception {
            if (stream == null) {
                return "";
            }
            StringBuilder result = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line);
                }
            }
            return result.toString();
        }

        private void requireSuccess(ApiResult result, String endpoint) {
            if (result.statusCode < 200 || result.statusCode >= 300) {
                throw new RuntimeException("GET " + endpoint + " returned HTTP " + result.statusCode
                        + (result.body.isBlank() ? "" : ": " + result.body));
            }
        }

        private static final class ProblemSummary {
            private int id;
        }

        private static final class ProblemPage {
            private List<ProblemSummary> entries;
        }

        private static final class SuggestionPage {
            private List<Suggestion> entries;
        }

        private static final class Suggestion {
            private ProblemSummary problem;
            private String type;
        }

        private record ApiResult(int statusCode, String body) { }
    }
}
