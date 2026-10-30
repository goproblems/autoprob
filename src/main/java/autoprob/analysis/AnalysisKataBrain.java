package autoprob.analysis;

import autoprob.KataBrain;

import java.io.*;
import java.text.DecimalFormat;
import java.util.Hashtable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.Map;
import java.util.Properties;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;

/** Engine transport owned exclusively by the new analysis worker. */
public final class AnalysisKataBrain extends KataBrain {
	private final Process process;
	private final Properties props;
	private boolean debugPrintKatago = false;
	private BufferedReader reader;
	private Map<String, KataAnalysisResult> results = new Hashtable<>();
	private Map<String, String> errors = new Hashtable<>();
	private volatile String lastProcessOutput;
	private static final DecimalFormat df = new DecimalFormat("0.00");
	private Thread thread;

	public AnalysisKataBrain(Properties props) throws Exception {
		this(props, null);
	}

	public AnalysisKataBrain(Properties props, String modelPathOverride) throws Exception {
		this.props = props;
		String kataPath = props.getProperty("katago").trim();
		String configPath = props.getProperty("kata.config").trim();
		System.out.println("KataGo config: " + new File(configPath).getAbsolutePath());
		modelPath = props.getProperty("kata.model").trim();
		String humanModelPath = props.getProperty("kata.human_model");
		if (modelPathOverride != null) {
			modelPath = modelPathOverride;
		}
		debugPrintKatago = Boolean.parseBoolean(props.getProperty("kata.debug_print", "false"));

		// create a native katago process
		ProcessBuilder processBuilder = new ProcessBuilder();
		processBuilder.redirectErrorStream(false);
		try {
            List<String> command = new ArrayList<>(List.of(kataPath, "analysis", "-config", configPath, "-model", modelPath));
            if (humanModelPath != null && !humanModelPath.isBlank()) {
                command.addAll(List.of("-human-model", humanModelPath));
            }
			// Only the new worker opts into an explicit result perspective.
            if (Boolean.parseBoolean(props.getProperty("analysis.force_black_perspective", "false"))) {
                command.addAll(List.of("-override-config", "reportAnalysisWinratesAs=BLACK"));
            }
            processBuilder.command(command);
			process = processBuilder.start();
			
            // Keep JSON stdout separate: interleaving native stderr log writes can corrupt a JSON line.
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader stderr = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = stderr.readLine()) != null) {
                        lastProcessOutput = line.length() > 500 ? line.substring(line.length() - 500) : line;
                        if (debugPrintKatago || line.contains("Error") || line.contains("Warning")
                            || line.contains("ready to begin handling requests")) System.err.println(line);
                    }
                } catch (IOException error) {
                    if (process.isAlive()) System.err.println("KataGo stderr: " + error.getMessage());
                }
            }, "katago-stderr");
            stderrThread.setDaemon(true);
            stderrThread.start();
			reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			
			try {
				thread = new Thread() {
					public void run() {
						System.out.println("katabrain Thread Running");
						try {
							AnalysisKataBrain.this.processKataResponses();
						} catch (InterruptedIOException e) {
							System.out.println("Thread was interrupted during I/O");
							Thread.currentThread().interrupt(); // Optional: re-interrupt the thread
						} catch (Exception e) {
							e.printStackTrace();
						} finally {
							if (process != null) {
								process.destroy(); // Ensure process is terminated when done
							}
						}
					}
				};

				thread.start();
			} catch (Exception e1) {
				e1.printStackTrace();
			}
		} catch (Exception e) {
			String err = e.getLocalizedMessage();
			String message = String.format("Failed to start the engine.\n\nError: %s",
					(err == null) ? "(No message)" : err);
			System.out.println(message);
			throw e;
		}
	}

    @Override
	public void stopKataBrain() {
        System.out.println("Stopping KataBrain...");
		if (thread != null) {
			thread.interrupt(); // Interrupt the thread, causing blocking I/O to throw an exception
		}
		if (process != null) {
			try {
				process.destroy(); // Attempt to terminate the process
				process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); // Wait for the process to terminate
				process.destroyForcibly(); // Force termination if not already done
			} catch (InterruptedException e) {
				System.out.println("Failed to terminate the process cleanly");
			}
		}
		if (reader != null) {
			try {
				reader.close(); // Close the reader to release any blocked I/O
			} catch (IOException e) {
				System.out.println("Failed to close the reader properly");
				e.printStackTrace();
			}
		}
	}

    @Override
	protected void processKataResponses() throws Exception {
		boolean printSummary = Boolean.parseBoolean(props.getProperty("kata.print_summary_result", "false"));
		boolean printWarnings = Boolean.parseBoolean(props.getProperty("kata.print_warnings", "true"));
		Gson gson = new Gson();
		String line;
		long startTime = 0;
		int total = 0;
		boolean started = false;
		while ((line = reader.readLine()) != null) {
			if (!line.isBlank()) {
				lastProcessOutput = line.length() > 500
					? line.substring(line.length() - 500) : line;
			}
            if (line.startsWith("{")) {
                JsonObject response = JsonParser.parseString(line).getAsJsonObject();
                if (response.has("error")) {
                    String error = response.get("error").getAsString();
                    if (!response.has("id")) {
                        throw new IOException("KataGo error: " + error);
                    }
                    synchronized (this) {
                        errors.put(response.get("id").getAsString(), error);
                    }
                    continue;
                }
                // Warning and control replies are not analysis positions.
                if (!response.has("rootInfo") || !response.has("turnNumber")
                    || (response.has("isDuringSearch") && response.get("isDuringSearch").getAsBoolean())) {
                    if (printWarnings && response.has("warning")) {
                        System.out.println("KataGo warning: " + response.get("warning").getAsString());
                    }
                    continue;
                }
            }
			if (line.contains("Uncaught exception")) {
				System.out.println("fatal error: " + line);
				throw new RuntimeException("fatal error: " + line);
			}
			if (line.contains("terminating with"))
				throw new RuntimeException("ending on katago error: " + line);
			
			// print to debug
			if (debugPrintKatago) {
				System.out.println("kata: " + line);
			} else if (printWarnings && line.contains("Warning:")) {
				System.out.println("kata: " + line);
			}

			if (line.startsWith("{")) {
				KataAnalysisResult kres = gson.fromJson(line, KataAnalysisResult.class);
				total++;
				double avgTime = (System.currentTimeMillis() - startTime) / (double)total;
				if (printSummary)
					System.out.println("> KBRAIN parsed: " + kres.id + ", turn: " + kres.turnNumber + ", score: " + df.format(kres.rootInfo.scoreLead) + ", avg ms: " + df.format(avgTime));

				synchronized (this) {
					// record result
					results.put(kres.id + kres.turnNumber, kres);
				}
			}
			else if (line.contains("ready to begin handling requests")) {
				System.out.println(line);
				startTime = System.currentTimeMillis();
				started = true;
			}
			else {
				if (started) {
					// print out other lines
					System.out.println(line);
				}
			}
		}
	}

    @Override
	public void doQuery(KataQuery query) throws Exception {
		Gson gson = new Gson();
		String qjson = gson.toJson(query, KataQuery.class);
		// System.out.println("KENG (" + "x" + ") moves: " + query.moves.size() + ", visits: " + query.maxVisits + ", query: " + qjson);
		
		PrintWriter pw = new PrintWriter(process.getOutputStream());
		pw.println(qjson);
		pw.flush();
        if (pw.checkError()) {
            throw new IOException("Could not write query to KataGo");
        }
	}

	/**
	 * Waits for a KataGo result. Timeout is read from props (kata.query.timeout.ms).
	 * A value of 0 means no timeout (wait indefinitely).
	 */
    @Override
	public KataAnalysisResult getResult(String id, int targetTurn) {
//		System.out.println("brain fetching: " + id + " : " + targetTurn);
        return getResult(id, targetTurn, Long.parseLong(props.getProperty("kata.query.timeout.ms", "0")));
    }

    public KataAnalysisResult getResult(String id, int targetTurn, long timeoutMs) {
		String nm = id + targetTurn; // lookup
		long startTime = System.currentTimeMillis();
		while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("KataGo analysis interrupted");
            }
			synchronized (this) {
				if (errors.containsKey(id)) {
					String errorMsg = errors.remove(id);
					KataAnalysisResult errorResult = new KataAnalysisResult();
					errorResult.id = id;
					errorResult.turnNumber = targetTurn;
					errorResult.error = errorMsg;
					return errorResult;
				}
				// getting the value removes it from our map
				if (results.containsKey(nm)) {
//					System.out.println("brain found: " + id + " : " + targetTurn);
					return results.remove(nm);
				}
			}
			if (!process.isAlive()) {
				KataAnalysisResult errorResult = new KataAnalysisResult();
				errorResult.id = id;
				errorResult.turnNumber = targetTurn;
				errorResult.error = "KataGo process exited with code " + process.exitValue()
					+ (lastProcessOutput == null ? "" : ": " + lastProcessOutput);
				return errorResult;
			}
			if (timeoutMs > 0 && System.currentTimeMillis() - startTime > timeoutMs) {
				System.err.println("KataGo query timed out after " + (timeoutMs / 1000) +
						"s for id=" + id + ", turn=" + targetTurn);
				KataAnalysisResult errorResult = new KataAnalysisResult();
				errorResult.id = id;
				errorResult.turnNumber = targetTurn;
				errorResult.error = "KataGo query timed out after " + (timeoutMs / 1000) + " seconds";
				return errorResult;
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("KataGo analysis interrupted");
			}
		}
	}

	/** Extract a string value for a given key from a JSON-like string, e.g. "id":"abc" → abc */
	private static String extractJsonStringValue(String text, String key) {
		String search = "\"" + key + "\":\"";
		int start = text.indexOf(search);
		if (start < 0) return null;
		start += search.length();
		int end = text.indexOf("\"", start);
		if (end < 0) return null;
		return text.substring(start, end);
	}
}
