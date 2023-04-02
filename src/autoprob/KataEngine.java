package autoprob;

import java.awt.Point;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Properties;

import com.google.gson.Gson;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;
import autoprob.katastruct.AllowMove;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;

public class KataEngine {
	private Process process;
	private BufferedReader reader;

//	public KataEngine(String kataPath, String configPath, String modelPath) throws Exception {

	public KataEngine(Properties props) throws Exception {
		String kataPath = props.getProperty("katago");
		String configPath = props.getProperty("kata.config");
		String modelPath = props.getProperty("kata.model");
		ProcessBuilder processBuilder = new ProcessBuilder();
		processBuilder.redirectErrorStream(true);
		try {
//			processBuilder.directory(new File(basePath));
			processBuilder.command(kataPath, "analysis", "-config", configPath, "-model", modelPath);
			process = processBuilder.start();
			
			reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
		} catch (Exception e) {
			String err = e.getLocalizedMessage();
			String message = String.format("Failed to start the engine.\n\nError: %s",
					(err == null) ? "(No message)" : err);
			System.out.println(message);
			throw e;
		}
	}

	public KataAnalysisResult analyzeNode(Node node, int visits, ArrayList<String> moves) throws Exception {
		Gson gson = new Gson();

		QueryBuilder qb = new QueryBuilder();

		// if we have a parent, build from that so we don't get ko issues
		KataQuery query;
		if (node.mom == null) {
			query = qb.buildQuery(node);
			query.analyzeTurns = new ArrayList<>();
			query.analyzeTurns.add(0); // 0 is the initial position, all we do here
		} else {
			query = qb.buildQueryFromMom(node);
		}
		
		query.id = "keng" + node.depth;
		query.maxVisits = visits;
		query.includePolicy = true;
		// required moves set?
		if (moves != null && moves.size() > 0) {
			var am = new AllowMove();
			am.player = query.initialPlayer;
			am.untilDepth = 1;
			am.moves = moves;
			query.allowMoves = new ArrayList<>();
			query.allowMoves.add(am);
		}
//		assert(query.analyzeTurns.size() == 0);
		String qjson = gson.toJson(query, KataQuery.class);
		String lm = "";
		MoveAction moveAction = node.getMoveAction();
		if (moveAction != null) {
			Point loc = moveAction.loc;
			lm = Intersection.toGTPloc(loc.x, loc.y, 19);
		}
		System.out.println("XXKENG (" + lm + ") moves: " + query.moves.size() + ", visits: " + query.maxVisits + ", query: " + qjson);
		
		PrintWriter pw = new PrintWriter(process.getOutputStream());
		pw.println(qjson);
		pw.flush();

		String line;
		while ((line = reader.readLine()) != null) {
			if (line.startsWith("{\"error")) {
				System.out.println(line);
				return null;
			}
			
			////////////// print to debug
//			System.out.println(line);

			if (line.startsWith("{\"error")) {
				throw new Exception("bad analysis: " + line);
			}
			
			if (line.startsWith("{")) {
				KataAnalysisResult kres = gson.fromJson(line, KataAnalysisResult.class);
				System.out.println("> KENG parsed: " + kres.id + ", turn: " + kres.turnNumber + ", score: " + kres.rootInfo.scoreLead);
				
				//// enable to debug ownership
//				kres.drawOwnership(node);
				
				return kres;
			}
			else if (line.contains("ready to begin handling requests")) {
				System.out.println(line);
			}
		}
		
		if (moves != null && moves.size() > 0) {
			// try again without moves
			return analyzeNode(node, visits, null);
		}
		
		throw new Exception("reached end of analyzeNode. last line: " + line);
//		return null;
	}

	public KataAnalysisResult analyzeNode(Node node, int visits) throws Exception {
		return analyzeNode(node, visits, null);
	}

	public KataAnalysisResult analyzeNode(Node node, int visits, double dist) throws Exception {
		ArrayList<String> moves = new ArrayList<String>();
		// look for locs within dist of a stone
		for (int x = 0; x < 19; x++)
			for (int y = 0; y < 19; y++) {
				double min = 5000;
				int idist = (int)dist;
				for (int dx = x - idist; dx <= x + idist; dx++)
					for (int dy = y - idist; dy <= y + idist; dy++) {
						if (node.board.inBoard(dx, dy))
							if (node.board.board[dx][dy].stone != 0) {
								double d = Math.max(Math.abs(dx - x), Math.abs(dy - y));
								min = Math.min(min, d);
							}
					}
				if (min <= dist) {
					moves.add(Intersection.toGTPloc(x, y, 19));
				}
			}
		return analyzeNode(node, visits, moves);
	}

}
