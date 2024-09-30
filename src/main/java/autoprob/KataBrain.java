package autoprob;

import java.io.*;
import java.text.DecimalFormat;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;

// runs katago in a thread, can handle multiple simul queries, aggregates and returns them
public class KataBrain {
	private final Process process;
	private final Properties props;
	private boolean debugPrintKatago = false;
	private BufferedReader reader;
	private Map<String, KataAnalysisResult> results = new Hashtable<>();
	private static final DecimalFormat df = new DecimalFormat("0.00");
	public String modelPath;
	private Thread thread;

	public KataBrain(Properties props) throws Exception {
		this(props, null);
	}

	public KataBrain(Properties props, String modelPathOverride) throws Exception {
		this.props = props;
		String kataPath = props.getProperty("katago").trim();
		String configPath = props.getProperty("kata.config").trim();
		modelPath = props.getProperty("kata.model").trim();
		String humanModelPath = props.getProperty("kata.human_model");
		if (modelPathOverride != null) {
			modelPath = modelPathOverride;
		}
		debugPrintKatago = Boolean.parseBoolean(props.getProperty("kata.debug_print", "false"));

		// create a native katago process
		ProcessBuilder processBuilder = new ProcessBuilder();
		processBuilder.redirectErrorStream(true);
		try {
			// pass in human model if we have it
			if (humanModelPath != null && !humanModelPath.isEmpty()) {
				processBuilder.command(kataPath, "analysis", "-config", configPath, "-model", modelPath, "-human-model", humanModelPath);
			} else {
				processBuilder.command(kataPath, "analysis", "-config", configPath, "-model", modelPath);
			}
			process = processBuilder.start();
			
			reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			
			try {
				thread = new Thread() {
					public void run() {
						System.out.println("katabrain Thread Running");
						try {
							KataBrain.this.processKataResponses();
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

	public void stopKataBrain() {
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

	protected void processKataResponses() throws Exception {
		boolean printSummary = Boolean.parseBoolean(props.getProperty("kata.print_summary_result", "false"));
		Gson gson = new Gson();
		String line;
		long startTime = 0;
		int total = 0;
		boolean started = false;
		while ((line = reader.readLine()) != null) {
			if (line.startsWith("{\"error")) {
				System.out.println("bad analysis: " + line);
				//TODO: process error
				continue;
			}
			if (line.startsWith("Uncaught exception")) {
				System.out.println("fatal error: " + line);
				throw new RuntimeException("fatal error: " + line);
			}
			if (line.contains("terminating with"))
				throw new RuntimeException("ending on katago error: " + line);
			
			// print to debug
			if (debugPrintKatago)
				System.out.println("kata: " + line);

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

	public void doQuery(KataQuery query) throws Exception {
		Gson gson = new Gson();
		String qjson = gson.toJson(query, KataQuery.class);
//		System.out.println("KENG (" + "x" + ") moves: " + query.moves.size() + ", visits: " + query.maxVisits + ", query: " + qjson);
		
		PrintWriter pw = new PrintWriter(process.getOutputStream());
		pw.println(qjson);
		pw.flush();
	}

	// tries until finds it
	// TODO: respond to errors
	public KataAnalysisResult getResult(String id, int targetTurn) {
//		System.out.println("brain fetching: " + id + " : " + targetTurn);
		String nm = id + targetTurn; // lookup
		while (true) {
			synchronized (this) {
				// getting the value removes it from our map
				if (results.containsKey(nm)) {
//					System.out.println("brain found: " + id + " : " + targetTurn);
					return results.remove(nm);
				}
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
			}
		}
	}
}
