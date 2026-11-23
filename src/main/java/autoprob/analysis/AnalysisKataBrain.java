package autoprob.analysis;

import autoprob.KataBrain;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Multiplexes independent queries on one model process. Only this transport opts into streaming. */
public final class AnalysisKataBrain extends KataBrain {
    private final Process process;
    private final Properties props;
    private final BufferedWriter writer;
    private final Object writeLock = new Object();
    private final Map<String, KataAnalysisResult> results = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    private final Map<String, AtomicInteger> activeQueries = new ConcurrentHashMap<>();
    private final Map<String, BlockingQueue<KataAnalysisResult>> streams = new ConcurrentHashMap<>();
    private final ThreadLocal<String> queryScope = new ThreadLocal<>();
    private final ThreadLocal<Integer> queryPriority = new ThreadLocal<>();
    private volatile String lastProcessOutput = "";

    public AnalysisKataBrain(Properties props, String requestedModel) throws Exception {
        this.props = props;
        modelPath = requestedModel == null ? props.getProperty("kata.model").trim() : requestedModel;
        List<String> command = new ArrayList<>(List.of(props.getProperty("katago").trim(), "analysis",
            "-config", props.getProperty("kata.config").trim(), "-model", modelPath));
        String humanModel = props.getProperty("kata.human_model", "").trim();
        if (!humanModel.isBlank()) command.addAll(List.of("-human-model", humanModel));
        List<String> overrides = new ArrayList<>();
        if (Boolean.parseBoolean(props.getProperty("analysis.force_black_perspective", "false"))) {
            overrides.add("reportAnalysisWinratesAs=BLACK");
        }
        for (String key : List.of("numAnalysisThreads", "numSearchThreadsPerAnalysisThread")) {
            String configured = props.getProperty("analysis.kata." + key);
            if (configured != null) {
                int value = Integer.parseInt(configured);
                if (value < 1 || value > 256) throw new IllegalArgumentException("Invalid " + key);
                overrides.add(key + "=" + value);
            }
        }
        if (!overrides.isEmpty()) command.addAll(List.of("-override-config", String.join(",", overrides)));
        process = new ProcessBuilder(command).start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread stderr = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lastProcessOutput = line.length() > 500 ? line.substring(line.length() - 500) : line;
                    if (Boolean.parseBoolean(props.getProperty("kata.debug_print", "false")) || line.contains("Error") || line.contains("Warning")) {
                        System.err.println(line);
                    }
                }
            } catch (IOException error) {
                if (process.isAlive()) System.err.println("KataGo stderr: " + error.getMessage());
            }
        }, "analysis-katago-stderr");
        stderr.setDaemon(true);
        stderr.start();
        Thread reader = new Thread(() -> {
            try { processKataResponses(); }
            catch (Exception error) { if (process.isAlive()) System.err.println("KataGo reader: " + error.getMessage()); }
            finally {
                process.destroy();
                synchronized (this) { notifyAll(); }
            }
        }, "analysis-katago-stdout");
        reader.setDaemon(true);
        reader.start();
    }

    public boolean isAlive() { return process.isAlive(); }

    public void beginScope(String scope) { queryScope.set(scope); }

    public void beginScope(String scope, int priority) { queryScope.set(scope); queryPriority.set(priority); }

    /** Cancels only this task's queries, leaving other users' searches running. */
    public void endScope(String scope) {
        queryScope.remove();
        queryPriority.remove();
        for (String id : List.copyOf(activeQueries.keySet())) {
            if (id.startsWith(scope)) {
                activeQueries.remove(id);
                streams.remove(id);
                try { terminateQuery(id); }
                catch (IOException error) { System.err.println("KataGo cancel: " + error.getMessage()); }
            }
        }
        streams.keySet().removeIf(id -> id.startsWith(scope));
        synchronized (this) {
            results.keySet().removeIf(id -> id.startsWith(scope));
            errors.keySet().removeIf(id -> id.startsWith(scope));
        }
    }

    public BlockingQueue<KataAnalysisResult> streamQuery(KataQuery query) throws Exception {
        scope(query);
        BlockingQueue<KataAnalysisResult> updates = new ArrayBlockingQueue<>(1);
        streams.put(query.id, updates);
        doQuery(query);
        return updates;
    }

    private void scope(KataQuery query) {
        String scope = queryScope.get();
        if (scope != null && !query.id.startsWith(scope)) query.id = scope + query.id;
    }

    @Override public void doQuery(KataQuery query) throws Exception {
        scope(query);
        if (query.priority == null) query.priority = queryPriority.get();
        activeQueries.put(query.id, new AtomicInteger(query.analyzeTurns == null ? 1 : query.analyzeTurns.size()));
        send(new Gson().toJson(query, KataQuery.class));
    }

    private void terminateQuery(String id) throws IOException {
        JsonObject command = new JsonObject();
        command.addProperty("id", "terminate:" + UUID.randomUUID());
        command.addProperty("action", "terminate");
        command.addProperty("terminateId", id);
        send(command.toString());
    }

    private void send(String json) throws IOException {
        synchronized (writeLock) {
            writer.write(json);
            writer.newLine();
            writer.flush();
        }
    }

    @Override protected void processKataResponses() throws Exception {
        Gson gson = new Gson();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("{")) continue;
                JsonObject response = JsonParser.parseString(line).getAsJsonObject();
                if (!response.has("id")) {
                    if (response.has("error")) throw new IOException("KataGo: " + response.get("error").getAsString());
                    continue;
                }
                String id = response.get("id").getAsString();
                if (!activeQueries.containsKey(id)) continue;
                if (response.has("warning")) {
                    if (Boolean.parseBoolean(props.getProperty("kata.print_warnings", "true"))) System.err.println(response.get("warning"));
                    continue;
                }
                KataAnalysisResult result = gson.fromJson(response, KataAnalysisResult.class);
                if (response.has("noResults")) result.error = "Query stopped before any search";
                BlockingQueue<KataAnalysisResult> stream = streams.get(id);
                boolean partial = Boolean.TRUE.equals(result.isDuringSearch);
                if (stream != null) {
                    // Never block stdout on HTTP or a slow consumer; retain only the newest snapshot.
                    if (!stream.offer(result)) { stream.poll(); stream.offer(result); }
                } else if (!partial) {
                    synchronized (this) {
                        if (result.isError()) errors.put(id, result.error);
                        else if (result.rootInfo != null) results.put(id + result.turnNumber, result);
                        notifyAll();
                    }
                }
                if (!partial) {
                    AtomicInteger remaining = activeQueries.get(id);
                    if (result.isError() || (remaining != null && remaining.decrementAndGet() <= 0)) activeQueries.remove(id);
                }
            }
        }
    }

    @Override public KataAnalysisResult getResult(String id, int targetTurn) {
        return getResult(id, targetTurn, Long.parseLong(props.getProperty("kata.query.timeout.ms", "0")));
    }

    public synchronized KataAnalysisResult getResult(String id, int targetTurn, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("KataGo analysis interrupted");
            if (errors.containsKey(id)) return error(id, targetTurn, errors.remove(id));
            KataAnalysisResult result = results.remove(id + targetTurn);
            if (result != null) return result;
            if (!process.isAlive()) return error(id, targetTurn, "KataGo exited: " + lastProcessOutput);
            if (timeoutMs > 0 && System.currentTimeMillis() - start >= timeoutMs) return error(id, targetTurn, "KataGo query timed out");
            try { wait(timeoutMs > 0 ? Math.min(1000, Math.max(1, timeoutMs - (System.currentTimeMillis() - start))) : 1000); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("KataGo analysis interrupted");
            }
        }
    }

    private KataAnalysisResult error(String id, int turn, String message) {
        KataAnalysisResult result = new KataAnalysisResult();
        result.id = id;
        result.turnNumber = turn;
        result.error = message;
        return result;
    }

    @Override public void stopKataBrain() {
        process.destroy();
        try { if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); }
        catch (InterruptedException interrupted) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
}
