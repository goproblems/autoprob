package autoprob.collection;

import autoprob.api.Problem;
import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.parse.Parser;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Runs a named collection detector against GoProblems API problem SGFs. */
public final class CollectionDetectionCommand {
    private final Properties props;
    private final CollectionDetector detector;
    private final GoProblemsApi api;

    public CollectionDetectionCommand(Properties props) {
        this.props = props;
        this.detector = createDetector(required(props, "collection"));
        this.api = new GoProblemsApi(props);
    }

    public void run() throws Exception {
        String idValue = props.getProperty("id");
        String startValue = props.getProperty("startid");
        String countValue = props.getProperty("count");

        if (idValue != null) {
            if (startValue != null || countValue != null) {
                throw new IllegalArgumentException("Use either id, or startid with count, not both");
            }
            testOne(Integer.parseInt(idValue), true);
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
            } else if (showNonMatchBoard) {
                System.out.println("Problem " + id + " does not match: " + result.reason());
                System.out.println(renderBoard(startingPosition.board));
            }
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
    }

    private CollectionDetector createDetector(String collectionName) {
        if ("center-tubes".equalsIgnoreCase(collectionName)) {
            return new CenterTubesDetector();
        }
        throw new IllegalArgumentException("Unknown collection detector: " + collectionName
                + ". Available detectors: center-tubes");
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

    private static final class ScanCounts {
        private int checked;
        private int matches;
        private int inactive;
        private int missing;
        private int errors;
    }

    private static final class GoProblemsApi {
        private static final int LIST_PAGE_SIZE = 50;

        private final Properties props;
        private final Gson gson = new Gson();
        private final long delayMillis;
        private long lastRequestAt;

        private GoProblemsApi(Properties props) {
            this.props = props;
            this.delayMillis = Long.parseLong(props.getProperty("detectcollection.delay.ms", "500"));
            if (delayMillis < 0) {
                throw new IllegalArgumentException("detectcollection.delay.ms cannot be negative");
            }
        }

        private Problem fetchProblem(int id) throws Exception {
            String endpoint = props.getProperty("api.problem.details", "api/v2/problems/{id}")
                    .replace("{id}", Integer.toString(id));
            ApiResult result = get(endpoint);
            if (result.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return null;
            }
            requireSuccess(result, endpoint);
            return gson.fromJson(result.body, Problem.class);
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

        private ApiResult get(String endpoint) throws Exception {
            rateLimit();
            String baseUrl = props.getProperty("baseurl", "https://www.goproblems.com/");
            URL url = URI.create(ensureTrailingSlash(baseUrl)).resolve(endpoint).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "autoprob-collection-detector/1.0");
            String apiKey = props.getProperty("apikey");
            if (apiKey != null && !apiKey.isBlank()) {
                connection.setRequestProperty("X-Api-Key", apiKey);
            }
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);

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

        private record ApiResult(int statusCode, String body) { }
    }
}
