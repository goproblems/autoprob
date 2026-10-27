package autoprob;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Properties;

import autoprob.go.Board;
import com.google.gson.Gson;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;
import autoprob.katastruct.AllowMove;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.KataQuery;

public class NodeAnalyzer {
	private boolean debugOwnership = false;
	private final Properties props;
	private static final DecimalFormat df = new DecimalFormat("0.00");

	public NodeAnalyzer(Properties props) {
		this.props = props;
	}

	public NodeAnalyzer(Properties props, boolean debugOwnership) {
		this.debugOwnership  = debugOwnership;
		this.props = props;
	}

	public KataAnalysisResult analyzeNode(KataBrain brain, Node node, int visits, ArrayList<String> moves) throws Exception {
		return analyzeNode(brain, node, visits, moves, "10k");
	}

	public KataAnalysisResult analyzeNode(KataBrain brain, Node node, int visits, ArrayList<String> moves, String humanSLrank) throws Exception {
		return analyzeNode(brain, node, visits, moves, humanSLrank, null);
	}

	public KataAnalysisResult analyzeNode(KataBrain brain, Node node, int visits, ArrayList<String> moves, String humanSLrank,
										  AllowMove deepAvoid) throws Exception {
		KataQuery.OverrideSettings overrideSettings = null;
		if (humanSLrank != null) {
			overrideSettings = new KataQuery.OverrideSettings();
			overrideSettings.humanSLProfile = "preaz_" + humanSLrank;
		}
		return analyzeNode(brain, node, visits, moves, overrideSettings, deepAvoid);
	}

	public KataAnalysisResult analyzeNode(KataBrain brain, Node node, int visits, ArrayList<String> moves, KataQuery.OverrideSettings overrideSettings) throws Exception {
		return analyzeNode(brain, node, visits, moves, overrideSettings, null);
	}

	// deepAvoid: moves one player may not play before a ply deep in the search, not just as the
	// first move. katago takes allowMoves or avoidMoves, never both, so with it the first move's
	// allowed points become an avoid list of every other point, pass included
	public KataAnalysisResult analyzeNode(KataBrain brain, Node node, int visits, ArrayList<String> moves,
										  KataQuery.OverrideSettings overrideSettings, AllowMove deepAvoid) throws Exception {
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

		query.id = "keng" + node.depth + "_" + Math.random();
		query.maxVisits = visits;
		query.includePolicy = true;

		// Set override settings if provided
		if (overrideSettings != null) {
			query.setOverrideSettings(overrideSettings);
		}

		// required moves set?
		if (deepAvoid != null) {
			String toMove = Intersection.color2katagoname(node.getToMove());
			query.avoidMoves = new ArrayList<>();
			if (moves != null && moves.size() > 0) {
				var first = new AllowMove();
				first.player = toMove;
				first.untilDepth = 1;
				first.moves = new ArrayList<>();
				java.util.Set<String> allowed = new java.util.HashSet<>(moves);
				for (int x = 0; x < 19; x++)
					for (int y = 0; y < 19; y++) {
						String gtp = Intersection.toGTPloc(x, y, 19);
						// a point in both lists would be avoided to an unspecified depth
						if (allowed.contains(gtp) || (deepAvoid.player.equals(toMove) && deepAvoid.moves.contains(gtp))) continue;
						first.moves.add(gtp);
					}
				first.moves.add("pass");
				query.avoidMoves.add(first);
			}
			query.avoidMoves.add(deepAvoid);
		} else if (moves != null && moves.size() > 0) {
			var am = new AllowMove();
			am.player = Intersection.color2katagoname(node.getToMove());
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
		boolean dbgNal = Boolean.parseBoolean(props.getProperty("kata.print_analyzer_query", "false"));
		if (dbgNal)
			System.out.println("NAL query (" + lm + ") moves: " + query.moves.size() + ", visits: " + query.maxVisits + ", query: " + qjson);
		
		brain.doQuery(query);
		KataAnalysisResult kres = brain.getResult(query.id, query.analyzeTurns.get(0));

		if (kres.isError() && brain.isAlive()) {
			// the engine sometimes stalls past the query timeout but recovers;
			// one retry saves the whole run from a transient stall
			System.out.println("analysis error (" + kres.error + "), retrying once");
			query.id = query.id + "r";
			brain.doQuery(query);
			kres = brain.getResult(query.id, query.analyzeTurns.get(0));
		}
		if (kres.isError()) {
			return kres;
		}

		if (dbgNal)
			System.out.println("> NAL parsed: " + kres.id + ", turn: " + kres.turnNumber + ", score: " + df.format(kres.rootInfo.scoreLead) + ", for " + kres.rootInfo.currentPlayer);
				
		if (debugOwnership) {
			System.out.println("Ownership after move " + lm + " depth: " + node.depth + ":");
			kres.drawOwnership(node);
			kres.drawNumericalOwnership(node);
		}
		
		//TODO if no moves come back, try without move restriction. should this really be possible though? i think not. probably a bug we needed it before.
				
		return kres;
		
//		if (moves != null && moves.size() > 0) {
//			// try again without moves
//			return analyzeNode(brain, node, visits, null);
//		}
	}

	public KataAnalysisResult analyzeNode(KataBrain brain, Node node, int visits) throws Exception {
		return analyzeNode(brain, node, visits, null);
	}

	public KataAnalysisResult analyzeNode(KataBrain brain, Node node, int visits, double dist, boolean useDistance, Board ignoreStones) throws Exception {
		return analyzeNode(brain, node, visits, dist, useDistance, ignoreStones, null);
	}

	// alsoAllow: further points the first move may be played at, such as a ko threat pattern
	public KataAnalysisResult analyzeNode(KataBrain brain, Node node, int visits, double dist, boolean useDistance,
										  Board ignoreStones, java.util.List<Point> alsoAllow) throws Exception {
		return analyzeNode(brain, node, visits, dist, useDistance, ignoreStones, alsoAllow, null);
	}

	// avoid: points the first move may not be played at, such as a ko capture the side to move
	// is not allowed to take
	public KataAnalysisResult analyzeNode(KataBrain brain, Node node, int visits, double dist, boolean useDistance,
										  Board ignoreStones, java.util.List<Point> alsoAllow,
										  java.util.Set<String> avoid) throws Exception {
		return analyzeNode(brain, node, visits, dist, useDistance, ignoreStones, alsoAllow, avoid, null);
	}

	// deepAvoid: moves one player may not play anywhere in the search before its untilDepth, null
	// for none
	public KataAnalysisResult analyzeNode(KataBrain brain, Node node, int visits, double dist, boolean useDistance,
										  Board ignoreStones, java.util.List<Point> alsoAllow,
										  java.util.Set<String> avoid, AllowMove deepAvoid) throws Exception {
		if (!useDistance) {
			return analyzeNode(brain, node, visits, null, "10k", deepAvoid);
		}
		ArrayList<String> moves = new ArrayList<>();
		// look for locs within dist of a stone
		for (int x = 0; x < 19; x++)
			for (int y = 0; y < 19; y++) {
				double min = 5000;
				int idist = (int)dist;
				for (int dx = x - idist; dx <= x + idist; dx++)
					for (int dy = y - idist; dy <= y + idist; dy++) {
						if (node.board.inBoard(dx, dy))
							if (node.board.board[dx][dy].stone != 0) {
								if (ignoreStones != null && ignoreStones.board[dx][dy].stone != 0) {
									continue; // false hit, this was a filled stone
								}

								double d = Math.max(Math.abs(dx - x), Math.abs(dy - y));
								min = Math.min(min, d);
							}
					}
				if (min <= dist) {
					moves.add(Intersection.toGTPloc(x, y, 19));
				}
			}
		if (alsoAllow != null) {
			for (Point p : alsoAllow) {
				String gtp = Intersection.toGTPloc(p.x, p.y, 19);
				if (node.board.board[p.x][p.y].stone == 0 && !moves.contains(gtp)) moves.add(gtp);
			}
		}
		if (avoid != null) moves.removeAll(avoid);
		return analyzeNode(brain, node, visits, moves, "10k", deepAvoid);
	}

}
