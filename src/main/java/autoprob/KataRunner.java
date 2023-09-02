package autoprob;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.Properties;

import autoprob.go.Node;
import autoprob.go.parse.Parser;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;

public class KataRunner {
	private static final DecimalFormat df = new DecimalFormat("0.00");

	private Properties props;
	
	public KataRunner(Properties prop) {
		this.props = prop;
	}

	public void startEngine(VisDetector vis) throws Exception {
		try {
			KataBrain brain = new KataBrain(props);
			
			// choose a mode depending on properties
			String path = props.getProperty("path");
			if (path == null) {
				throw new RuntimeException("you must pass in a path");
			}
			File f = new File(path);
			if (!f.exists()) {
				System.err.println("no such file or directory: " + path);
				return;
			}
			System.out.println("reading from: " + path + ", is file: " + f.isFile());
			int searchVisits = Integer.parseInt(props.getProperty("search.visits"));
			// may point to file, or to directory
			if (f.isFile()) {
				int targetTurn = 0;
				if (props.containsKey("turn")) {
					targetTurn = Integer.parseInt(props.getProperty("turn"));
				}
				// also parse out of the file name convention if ends in _mNUMBER.sgf
				if (targetTurn == 0) {
					String name = f.getName();
					int idx = name.lastIndexOf("_m");
					if (idx > 0) {
						String num = name.substring(idx + 2, name.length() - 4);
						targetTurn = Integer.parseInt(num);
						System.out.println("parsing target turn from file name: " + targetTurn);
					}
				}
				if (targetTurn > 0) {
					System.out.println("testing game " + path + ": " + " at " + targetTurn);
					int found = testAnalyze(brain, path, f.getName(), vis, targetTurn, searchVisits);
					System.out.println("target found: " + found);
				} else {
					// search whole game
					System.out.println("searching game " + path);
					int found = testAnalyze(brain, path, f.getName(), vis, 0, searchVisits);
					System.out.println("target found: " + found);
				}
			} else {
				// directory search
				File dir = new File(path);
				File[] files = dir.listFiles();
				int cnt = 0;
				int targetProblems = Integer.parseInt(props.getProperty("search.directory.max_finds"));;
				int foundProblems = 0;
				int i = readIterationCount(dir);
				for (; i < files.length; i++) {
					writeIterationCounter(i, dir);
					System.out.println(cnt + " -- examining: " + files[i].getName());
					int found = testAnalyze(brain, files[i].getAbsolutePath(), files[i].getName(), vis, 0, searchVisits);
					foundProblems += found;
					cnt++;
					if (found > 0) {
						System.out.println("searched games: " + cnt + ", total problems found: " + foundProblems);
						if (foundProblems >= targetProblems)
							return;
					}
					
				}
			}

//			int exitCode = process.waitFor();
//			System.out.println("\nExited with error code : " + exitCode);
		} catch (Exception e) {
			String err = e.getLocalizedMessage();
			String message = String.format("Failed to start the engine.\n\nError: %s",
					(err == null) ? "(No message)" : err);
			System.out.println(message);
			throw e;
		}
	}

	private int readIterationCount(File dir) {
		var f = new File(dir, "zpos");
		try {
			var fr = new FileReader(f);
			String s = Files.readString(f.toPath());
			System.out.println("-> starting dir scan at " + s);
			return Integer.parseInt(s) + 1;
		} catch (IOException e) {
			return 0;
		}
	}

	private void writeIterationCounter(int i, File dir) {
		var f = new File(dir, "zpos");
		try {
			var fw = new FileWriter(f);
			fw.write("" + i);
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private int testAnalyze(KataBrain brain, String sgfPath, String fileName, VisDetector vis, int onlySearchTurn, int maxVisits) throws Exception {
		System.out.println("testing game " + fileName + ": " + " at " + onlySearchTurn  + " with " + maxVisits + " visits");

		// get sgf
		String sgf;
		try {
			sgf = Files.readString(Path.of(sgfPath));
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("bailing on this SGF");
			return 0;
		}
		
		// load SGF
		var parser = new Parser();
		Node node;
		try {
			node = parser.parse(sgf);
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("bad sgf");
			return 0;
		}
		
		QueryBuilder qb = new QueryBuilder();
		KataQuery query = qb.buildQuery(node);
		query.id = "auto:" + fileName;
		query.maxVisits = maxVisits;
		query.includePolicy = true;
		// reduce search?
		if (onlySearchTurn > 0) {
			query.analyzeTurns.clear();
			query.analyzeTurns.add(onlySearchTurn);
			query.analyzeTurns.add(onlySearchTurn + 1);
		}
		
		brain.doQuery(query); // kick off katago

        Node n = node;
        int foundCount = 0;
        int targetTurn = onlySearchTurn; // next one to parse
        // move node pointer
        for (int adv = 0; adv < targetTurn; adv++)
        	n = n.favoriteSon();

        KataAnalysisResult kprev = null;
        for (int resultsProcessed = 0; resultsProcessed < query.analyzeTurns.size(); resultsProcessed++) {
        	KataAnalysisResult kres = brain.getResult(query.id, targetTurn);
        	System.out.println("parsed: " + kres.id + ", turn: " + kres.turnNumber + ", score: " + df.format(kres.rootInfo.scoreLead) + ", " + resultsProcessed);
        	if (kres.turnNumber > 0)
        		System.out.println(query.moves.get(kres.turnNumber - 1));
				
			targetTurn++;

			if (kprev != null) {
				// run detector
				boolean forceDetect = false;
				// maybe override valid if instructed
				if (onlySearchTurn > 0) {
					// only when looking at a single move
					if (props.containsKey("forceproblem") &&
							Boolean.parseBoolean(props.getProperty("forceproblem"))) {
						forceDetect = true;
					}
				}
				ProblemDetector detector;
				if (props.containsKey("type") &&
                        props.getProperty("type").equals("shape")) {
					detector = new ShapeProblemDetector(kprev, kres, n, props);
				} else {
					detector = new ProblemDetector(kprev, kres, n, props);
				}
				detector.detectProblem(brain, forceDetect);
				if (detector.validProblem) {
					// valid problem according to detector
					if (vis.newDetection(brain, detector, fileName)) {
						foundCount++;
						System.out.println("found problems: " + foundCount);
					} else {
						System.out.println("problem not accepted");
					}
				}
				// move node down to track
				if (n != null)
					n = n.favoriteSon();
			}
			
			// slide
			kprev = kres;
		}
		System.out.println("found problems: " + foundCount);
		return foundCount;
	}

	private Process versionCheck(ProcessBuilder processBuilder, String kataPath) throws IOException {
		processBuilder.command(kataPath, "version");
		Process process = processBuilder.start();

		BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

		String line;
		while ((line = reader.readLine()) != null) {
			System.out.println(line);
		}
		return process;
	}
}
