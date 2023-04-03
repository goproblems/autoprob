package autoprob;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Properties;

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
		boolean dbgNal = Boolean.parseBoolean(props.getProperty("kata.printanalyzerquery", "false"));
		if (dbgNal)
			System.out.println("NAL query (" + lm + ") moves: " + query.moves.size() + ", visits: " + query.maxVisits + ", query: " + qjson);
		
		brain.doQuery(query);
		KataAnalysisResult kres = brain.getResult(query.id, query.analyzeTurns.get(0));

		if (dbgNal)
			System.out.println("> NAL parsed: " + kres.id + ", turn: " + kres.turnNumber + ", score: " + df.format(kres.rootInfo.scoreLead));
				
		if (debugOwnership) {
			kres.drawOwnership(node);
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

	public KataAnalysisResult analyzeNode(KataBrain brain, Node node, int visits, double dist, boolean useDistance) throws Exception {
		if (!useDistance) {
			return analyzeNode(brain, node, visits, null);
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
								double d = Math.max(Math.abs(dx - x), Math.abs(dy - y));
								min = Math.min(min, d);
							}
					}
				if (min <= dist) {
					moves.add(Intersection.toGTPloc(x, y, 19));
				}
			}
		return analyzeNode(brain, node, visits, moves);
	}

}
