package autoprob.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.software.os.OSProcess;

import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Local-only probes. Missing permissions, drivers or optional commands never stop analysis. */
public final class ResourceTelemetryCollector {
    private final Properties properties;
    private final boolean enabled;
    private final int activeSeconds;
    private final int idleSeconds;
    private final int timeoutMillis;
    private SystemInfo system;
    private String hostId;
    private long[] previousTicks;
    private Map<Long, ProcessTimes> previousProcesses = Map.of();
    private long previousProcessNanos;
    private long lastSampleNanos;
    private long sequence;
    private volatile JsonObject latest;

    private record ProcessTimes(long startedAt, long cpuMillis) {}

    public ResourceTelemetryCollector(Properties properties) {
        this.properties = properties;
        enabled = Boolean.parseBoolean(properties.getProperty("analysis.telemetry.enabled", "true"));
        activeSeconds = bounded("analysis.telemetry.active.interval.seconds", 5, 2, 300);
        idleSeconds = bounded("analysis.telemetry.idle.interval.seconds", 20, 2, 300);
        timeoutMillis = bounded("analysis.telemetry.command.timeout.ms", 2000, 100, 10000);
    }

    private int bounded(String key, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(properties.getProperty(key, String.valueOf(fallback)));
        if (value < minimum || value > maximum) throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        return value;
    }

    public JsonObject latest() {
        JsonObject value = latest;
        return value == null ? null : value.deepCopy();
    }

    public boolean sampleIfDue(int activeTasks, String model, long revision) {
        if (!enabled) return false;
        long now = System.nanoTime();
        int interval = activeTasks > 0 ? activeSeconds : idleSeconds;
        if (lastSampleNanos != 0 && now - lastSampleNanos < TimeUnit.SECONDS.toNanos(interval)) return false;
        lastSampleNanos = now;
        JsonObject collected = sample(activeTasks, model, revision);
        collected.addProperty("intervalSeconds", interval);
        latest = collected;
        return true;
    }

    private JsonObject sample(int activeTasks, String model, long revision) {
        JsonObject result = new JsonObject();
        JsonArray errors = new JsonArray();
        JsonObject host = new JsonObject();
        JsonObject process = new JsonObject();
        List<ProcessHandle> handles = new ArrayList<>();
        handles.add(ProcessHandle.current());
        ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).forEach(handles::add);
        Set<Long> pids = new HashSet<>();
        handles.forEach(handle -> pids.add(handle.pid()));
        try {
            if (system == null) system = new SystemInfo();
            String name = system.getOperatingSystem().getNetworkParams().getHostName();
            if (hostId == null) {
                String configured = properties.getProperty("analysis.telemetry.host.id", "").trim();
                String uuid = system.getHardware().getComputerSystem().getHardwareUUID();
                String identity = uuid == null || uuid.isBlank() || "unknown".equalsIgnoreCase(uuid) ? name : uuid;
                hostId = configured.isEmpty() ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))) : configured;
            }
            host.addProperty("id", hostId);
            host.addProperty("name", properties.getProperty("analysis.telemetry.host.name", name));
            host.addProperty("os", System.getProperty("os.name"));
            CentralProcessor cpu = system.getHardware().getProcessor();
            int cores = cpu.getLogicalProcessorCount();
            host.addProperty("logicalProcessors", cores);
            long[] ticks = cpu.getSystemCpuLoadTicks();
            if (previousTicks == null) host.add("cpuPercent", JsonNull.INSTANCE);
            else host.addProperty("cpuPercent", Math.max(0, Math.min(100, cpu.getSystemCpuLoadBetweenTicks(previousTicks) * 100)));
            previousTicks = ticks;
            long total = system.getHardware().getMemory().getTotal();
            if (total > 0) {
                host.addProperty("memoryTotalBytes", total);
                host.addProperty("memoryAvailableBytes", system.getHardware().getMemory().getAvailable());
            }
            Map<Long, ProcessTimes> times = new HashMap<>();
            long memory = 0;
            long cpuMillis = 0;
            boolean memoryComplete = true;
            boolean cpuComplete = previousProcessNanos != 0;
            for (ProcessHandle handle : handles) {
                OSProcess info = system.getOperatingSystem().getProcess(Math.toIntExact(handle.pid()));
                if (info == null) {
                    if (handle.isAlive()) { memoryComplete = false; cpuComplete = false; }
                    continue;
                }
                memory += info.getResidentSetSize();
                ProcessTimes current = new ProcessTimes(info.getStartTime(), info.getKernelTime() + info.getUserTime());
                times.put(handle.pid(), current);
                ProcessTimes previous = previousProcesses.get(handle.pid());
                if (previous == null || previous.startedAt != current.startedAt) cpuComplete = false;
                else cpuMillis += Math.max(0, current.cpuMillis - previous.cpuMillis);
            }
            long measuredAt = System.nanoTime();
            process.addProperty("processCount", times.size());
            if (memoryComplete) process.addProperty("memoryRssBytes", memory);
            if (cpuComplete && measuredAt > previousProcessNanos && cores > 0) {
                process.addProperty("cpuPercent", Math.min(100, cpuMillis * 100_000_000.0 / (measuredAt - previousProcessNanos) / cores));
            }
            previousProcesses = times;
            previousProcessNanos = measuredAt;
        } catch (Exception | LinkageError unavailable) {
            errors.add("system_metrics_unavailable");
            String configured = properties.getProperty("analysis.telemetry.host.id", "").trim();
            if (!host.has("id")) {
                String fallback;
                try { fallback = InetAddress.getLocalHost().getHostName(); }
                catch (Exception unavailableHost) { fallback = "unknown-" + UUID.randomUUID(); }
                if (hostId == null) hostId = configured.isEmpty() ? fallback : configured;
                host.addProperty("id", hostId);
                host.addProperty("name", properties.getProperty("analysis.telemetry.host.name", fallback));
            }
            host.addProperty("os", System.getProperty("os.name"));
        }
        result.add("host", host);
        result.add("process", process);
        try { result.add("gpus", gpuMetrics(pids)); }
        catch (Exception unavailable) { result.add("gpus", new JsonArray()); errors.add("gpu_metrics_unavailable"); }
        String platform = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "windows" : "linux";
        String configuredCommand = properties.getProperty("analysis.telemetry.command." + platform, "").trim();
        if (!configuredCommand.isEmpty()) {
            try {
                List<String> command = new ArrayList<>();
                String ids = pids.stream().sorted().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
                for (JsonElement item : JsonParser.parseString(configuredCommand).getAsJsonArray()) command.add(item.getAsString().replace("{pids}", ids));
                JsonObject custom = JsonParser.parseString(run(command)).getAsJsonObject();
                for (String section : List.of("host", "process")) {
                    if (custom.has(section)) custom.getAsJsonObject(section).entrySet().forEach(entry -> result.getAsJsonObject(section).add(entry.getKey(), entry.getValue()));
                }
                if (custom.has("gpus")) {
                    result.add("gpus", custom.getAsJsonArray("gpus"));
                    JsonArray retained = new JsonArray();
                    for (JsonElement error : errors) if (!"gpu_metrics_unavailable".equals(error.getAsString())) retained.add(error);
                    errors = retained;
                }
            } catch (Exception unavailable) { errors.add("custom_command_failed"); }
        }
        result.addProperty("schemaVersion", 1);
        result.addProperty("sequence", ++sequence);
        result.addProperty("activeTasks", activeTasks);
        result.addProperty("configurationRevision", revision);
        result.addProperty("model", model);
        result.add("errors", errors);
        return result;
    }

    private JsonArray gpuMetrics(Set<Long> pids) throws Exception {
        String executable = properties.getProperty("analysis.telemetry.nvidia_smi", "nvidia-smi");
        String devices = run(List.of(executable, "--query-gpu=uuid,name,memory.total,memory.used,utilization.gpu", "--format=csv,noheader,nounits"));
        Map<String, Long> owned = new HashMap<>();
        Set<String> unknown = new HashSet<>();
        boolean processQuery = false;
        boolean matched = false;
        try {
            String apps = run(List.of(executable, "--query-compute-apps=pid,gpu_uuid,used_gpu_memory", "--format=csv,noheader,nounits"));
            processQuery = true;
            for (String line : apps.lines().filter(value -> !value.isBlank()).toList()) {
                List<String> row = csv(line);
                if (row.size() != 3 || !pids.contains(Long.parseLong(row.get(0)))) continue;
                matched = true;
                Long bytes = mebibytes(row.get(2));
                if (bytes == null) unknown.add(row.get(1));
                else owned.merge(row.get(1), bytes, Long::sum);
            }
        } catch (Exception unavailable) { processQuery = false; }
        JsonArray result = new JsonArray();
        for (String line : devices.lines().filter(value -> !value.isBlank()).toList()) {
            List<String> row = csv(line);
            if (row.size() != 5) throw new IllegalArgumentException("Unexpected nvidia-smi columns");
            JsonObject gpu = new JsonObject();
            gpu.addProperty("id", row.get(0));
            gpu.addProperty("name", row.get(1));
            nullable(gpu, "memoryTotalBytes", mebibytes(row.get(2)));
            nullable(gpu, "memoryUsedBytes", mebibytes(row.get(3)));
            nullable(gpu, "utilizationPercent", numeric(row.get(4)));
            // WDDM/WSL may not expose PID attribution. An absent match is not proof of zero usage.
            nullable(gpu, "clientMemoryUsedBytes", !processQuery || unknown.contains(row.get(0)) || !matched && pids.size() > 1 ? null : owned.getOrDefault(row.get(0), 0L));
            result.add(gpu);
        }
        return result;
    }

    private static void nullable(JsonObject object, String key, Number value) {
        if (value == null) object.add(key, JsonNull.INSTANCE);
        else object.addProperty(key, value);
    }

    private static Double numeric(String value) {
        try { double parsed = Double.parseDouble(value); return Double.isFinite(parsed) && parsed >= 0 ? parsed : null; }
        catch (NumberFormatException unsupported) { return null; }
    }

    private static Long mebibytes(String value) {
        Double parsed = numeric(value);
        return parsed == null ? null : Math.round(parsed * 1024 * 1024);
    }

    private static List<String> csv(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) { values.add(value.toString().trim()); value.setLength(0); }
            else value.append(c);
        }
        values.add(value.toString().trim());
        return values;
    }

    private String run(List<String> command) throws Exception {
        Process probe = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!probe.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) throw new IllegalStateException("Metrics command timed out");
            byte[] output = probe.getInputStream().readNBytes(65537);
            if (probe.exitValue() != 0 || output.length > 65536) throw new IllegalStateException("Metrics command failed");
            return new String(output, StandardCharsets.UTF_8);
        } finally {
            if (probe.isAlive()) {
                probe.descendants().forEach(ProcessHandle::destroyForcibly);
                probe.destroyForcibly();
            }
        }
    }
}
