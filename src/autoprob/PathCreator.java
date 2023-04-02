package autoprob;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Properties;

import autoprob.go.*;
import autoprob.go.action.MoveAction;
import autoprob.go.vis.BasicGoban;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

// creates solution and refutation paths given a problem
public class PathCreator {
    private static final DecimalFormat df = new DecimalFormat("0.00");
	
	// generation options
	public class GenOptions {
		public boolean altRefutes = false;
		public boolean altChallenges = false; // alternative ways to test human on correct line
		public int bailNum = 40; // max moves in tree
		public int bailDepth = 2; // only bail if at least this deep
		public double minPrior = 0.04; // for determining interesting variations, require this
		public boolean onlyConsiderNear = true; // tell kata to only look at possible moves near existing stones
		public double considerNearDist = 2.1; // how close for near moves
		
		@Override
		public String toString() {
			return "bail num: " + bailNum + ", bail depth: " + bailDepth;
		}
	}

	public static final int MOVE_VISITS_DEF = 1000;
	public static final double EXTRA_SOLUTION_THRESHOLD = 10; // scores within this range
	public static final int MIN_VISITS_WRONG = 10; // consider wrong moves with this many visits
	public static final double TENUKI_DIST = 2.7; // a move this far away from any other is a tenuki
	public static final double TENUKI_DIST_ERR = 2.5; // a move this far away from any other is a tenuki
	public static final double OWNERSHIP_THRESHOLD = 1.1;
	public static final double MOVE_DELTA_OWNERSHIP_THRESHOLD = 0.8; // how moves affect ownership
	public static final int MIN_OWNERSHIP_CHANGE_INTEREST = 5; // 
	public static final int MIN_GOOD_RESPONSE_VISITS = 5;
	public static final double MIN_GOOD_RESPONSE_POLICY = 0.05;
	public static final double MIN_GOOD_DEEP_RESPONSE_VISIT_RATIO = 0.5;
	public static final double MAX_VULN_INTEREST = 2.1; // 
	
	private int totalVarMoves = 0; // how many have been added to tree
	private Node firstSol = null; // first solution we find
	private final ProblemDetector det;
	private final Properties props;

	public PathCreator(ProblemDetector det, Properties props) {
		this.det = det;
		this.props = props;
	}

	// RIGHT: we are in a correct variation -- no errors by human
	private void handleRightMoveOption(KataBrain brain, Node node, BasicGoban probGoban, int depth, MoveInfo mi, KataAnalysisResult kar, GenOptions gopts, NodeChangeListener ncl) throws Exception {
		boolean isResponse = (depth & 1) == 1; // are we in a computer response move?
		double score = mi.scoreLead;
		Point p = Intersection.gtp2point(mi.move);
		double nearest = findNearest(node.board, p);
		double baseline = kar.rootInfo.scoreLead;
		
		// for right sequences, we END with a human move
		// we always respond to a good human move
		// try to add good human moves to the tree
		// there must always be at least one correct human move. there may be mistakes we add too if they look interesting.
		
		if (depth == 0) System.out.println();

		// human or computer move?
		if (isResponse) {
			// COMPUTER
			if (Math.abs(baseline - score) < EXTRA_SOLUTION_THRESHOLD) {
				// good response relative to the situation
				// but we have to check if it's interesting to add to the tree -- if it challenges the human in a way that makes sense
				// not necessary to add this depending on situation
				

				// if too far from other moves, not interesting
				if (nearest > TENUKI_DIST_ERR) return;
				
				System.out.println("  _considering_ response: " + node.printPath2Here() + ", " + mi.move + ", visits: " + mi.visits + ", policy: " + df.format(mi.prior * 1000.0));

				if (mi.visits < MIN_GOOD_RESPONSE_VISITS && mi.prior < MIN_GOOD_RESPONSE_POLICY) {
					System.out.println("  bad visits and policy");
					return;
				}
				if (firstSol != null && node.depth > firstSol.depth) {
					// check vs first sol depth, don't exceed too much
					if (mi.visits < MIN_GOOD_DEEP_RESPONSE_VISIT_RATIO * kar.rootInfo.visits) {
						System.out.println("  too deep");
						return;
					}
				}
				
				if (!gopts.altRefutes && node.babies.size() > 0) return; // already handled this var (we already refuted)
				
				// if this is not an intuitive move, don't bother
				if (mi.prior < gopts.minPrior) {
					System.out.println("  low prior " + mi.prior + " for " + mi.move);
					return;
				}
				
				// it's only interesting if it threatens to change life status
				int delta = calcPassDelta(brain, node, p, gopts);
		        if (delta < MIN_OWNERSHIP_CHANGE_INTEREST) {
		        	System.out.println("  response rejected, no threat: " + delta);
		        	return; // no threat, so don't add to tree
		        }

				// okay we have decided it's interesting, let's add the response and challenge the human
				System.out.println("  interesting response: " + mi.move + ", visits: " + mi.visits + ", policy: " + df.format(mi.prior * 1000.0) + ", " + node.printPath2Here());
		        Node tike = node.addBasicMove(p.x, p.y);
		        // recurse for responses
		        genPathRecurse(brain, tike, probGoban, depth + 1, true, gopts, ncl);
			} else {
				// bad move -- ignore
			}
		} else {
			// HUMAN
			if (Math.abs(baseline - score) < EXTRA_SOLUTION_THRESHOLD) {
				// they are making a correct move
				System.out.println("  d:" + depth + " >> human sol: " + mi.move + ", nearest: " + nearest + ", visits: " + mi.visits + ", policy: " + df.format(mi.prior * 1000.0) + ", score: " + score + ", " + node.printPath2Here());

				// check move isn't a tenuki
				if (nearest > TENUKI_DIST) {
					return;
				}
					
				// validate this is actually right and doesn't blow it
				int delta = calcMoveDelta(brain, node, p, kar, gopts);
		        if (delta > MIN_OWNERSHIP_CHANGE_INTEREST) {
		        	System.out.println("  not really a good move: " + mi.move + ", delta: " + delta);
		        	return;
		        }
			        
		        Node tike = node.addBasicMove(p.x, p.y);
		        tike.result = Intersection.RIGHT;
		        if (firstSol == null)
		        	firstSol = tike;
		        if (depth > 1) {
		        	node.mom.result = 0; // this becomes not a solution
		        	if (firstSol == node.mom)
		        		firstSol = tike; // update to deeper in tree
		        }

		        if (totalVarMoves++ > gopts.bailNum && depth > gopts.bailDepth) {
					System.out.println("$$$$$$$$$ human solve bail");
					return;
				}
		        
		        // recurse for responses
		        genPathRecurse(brain, tike, probGoban, depth + 1, true, gopts, ncl);
			} else {
				// bad move
				System.out.println("  _considering_ new mistake: " + node.printPath2Here() + ", " + mi.move + ", visits: " + mi.visits + ", policy: " + df.format(mi.prior * 1000.0) + ", score: " + score);
				
				if (nearest > TENUKI_DIST_ERR)
					return; // if too far from other moves, not interesting
				
				if (mi.visits < MIN_VISITS_WRONG && mi.prior < MIN_GOOD_RESPONSE_POLICY) {
					if (depth > 0) {
			        	System.out.println("  too few visits and policy: " + mi.move + ", visits: " + mi.visits);
						return; // too obscure
					} else {
						// ensure within good starting moves
						if (det.fullOwnershipChanges.contains(p)) {
							System.out.println("  fullOwnershipChanges contains " + mi.move + " so okay to explore");
						} else {
							System.out.println("  fullOwnershipChanges does not contain " + mi.move);
							return;
						}
					}
				}
				
//					if (mi.prior < 0.015) {
//			        	System.out.println("  too low policy on root: " + mi.move + ", visits: " + mi.visits);
//						return; // too obscure
//					}
				if (depth > 3 && mi.prior < 0.02) {
					System.out.println("  too weird deep");
					return; // too weird this deep
				}
				if (countEmptyShots(p, node) >= 3)
					return; // looks on outside

				// it's only interesting if it threatens to change life status
				int delta = calcPassDelta(brain, node, p, gopts);
		        if (delta < MIN_OWNERSHIP_CHANGE_INTEREST) {
		        	System.out.println("  mistake has bad ownership change: " + mi.move + ", delta: " + delta);
		        	return; // no threat, so don't add to tree
		        }
		        
		        // this human move should be answered
		        System.out.println("  will respond to mistake: " + node.printPath2Here() + ", " + mi.move + ", visits: " + mi.visits + ", policy: " + df.format(mi.prior * 1000.0) + ", score: " + score);
		        // recurse
		        Node tike = node.addBasicMove(p.x, p.y);
		        genPathRecurse(brain, tike, probGoban, depth + 1, false, gopts, ncl);
			}
		}
	}

	// WRONG: we are in a wrong variation -- one more more human errors
	private void handleWrongMoveOption(KataBrain brain, Node node, BasicGoban probGoban, int depth, MoveInfo mi,
			KataAnalysisResult kar, GenOptions gopts, NodeChangeListener ncl) throws Exception {
		boolean isResponse = (depth & 1) == 1; // are we in a computer response move?
		double score = mi.scoreLead;
		Point p = Intersection.gtp2point(mi.move);
		double nearest = findNearest(node.board, p);
		double baseline = kar.rootInfo.scoreLead;
		
		// for wrong sequences, we END with a computer move, which is the refutation

		// human or computer move?
		if (isResponse) {
			// computer
			if (Math.abs(baseline - score) < EXTRA_SOLUTION_THRESHOLD) {
				// good response relative to the situation
				// necessary to add so we refute
				//TODO maybe only add if not already a good refutation. eventually, figure out best refutation. or possibly make them choices
				
				// if too far from other moves, not interesting
				if (!gopts.altRefutes && node.babies.size() > 0) return; // already handled this var
				if (nearest > TENUKI_DIST) {
					System.out.println("WARNING: oddly distant refutation move " + mi.move);
				}

				// okay we have decided it's interesting, let's add the response and challenge the human
				System.out.println("  refutation: " + mi.move + ", visits: " + mi.visits + ", " + node.printPath2Here());
		        Node tike = node.addBasicMove(p.x, p.y);
		        // recurse for responses
		        genPathRecurse(brain, tike, probGoban, depth + 1, false, gopts, ncl);
			} else {
				// bad move -- ignore
			}
		} else {
			// human is trying this
			if (Math.abs(baseline - score) < EXTRA_SOLUTION_THRESHOLD) {
				// good response relative to the situation
				
				// check if it's relevant
				
				double dist = distance2vulnerable(p);
				if (mi.visits > 5 || mi.prior > 0.01) // cut down spam
					System.out.println("  _considering_ human wrong path attempt: " + node.printPath2Here() + ", " + mi.move + ", visits: " + mi.visits + ", policy: " + df.format(mi.prior * 1000.0));
				if (dist > MAX_VULN_INTEREST) {
//					System.out.println("  too dist to vuln: " + dist);
					return;
				}
				if (nearest > TENUKI_DIST_ERR) {
					System.out.println("  tenuki: " + nearest);
					return;
				}
				if (countEmptyShots(p, node) >= 3)
					return; // looks on outside
				
				// only consider if enough visits were found, or the policy looked interesting
				double minPolicy = 0.03;
				if (mi.visits < MIN_VISITS_WRONG && mi.prior < minPolicy)
					return; // too obscure
				if (totalVarMoves++ > gopts.bailNum && depth > gopts.bailDepth) {
					System.out.println("$$$$$$$$$$$$$$ bail wrong");
					return;
				}

				// it's only interesting if it threatens to change life status
				int delta = calcPassDelta(brain, node, p, gopts);
		        if (delta < MIN_OWNERSHIP_CHANGE_INTEREST) {
		        	return; // no threat, so don't add to tree
		        }
				
		        System.out.println("  human wrong path attempt: " + node.printPath2Here() + ", " + mi.move + ", visits: " + mi.visits + ", policy: " + df.format(mi.prior * 1000.0));
				Node tike = node.addBasicMove(p.x, p.y);
		        // recurse for responses
		        genPathRecurse(brain, tike, probGoban, depth + 1, false, gopts, ncl);
			} else {
				// bad move in a bad var
			}
		}
	}

	// how many directions can we shoot a line off the board unimpeded?
	// approximation for empty space
	private int countEmptyShots(Point p, Node node) {
		int cnt = 0;
		// count edges specially -- add one if on edge
		if (p.x == 0 || p.y == 0 || p.x == 18 || p.y == 18)
			cnt = 1;
		
		for (int dx = -1; dx <= 1; dx += 1)
			for (int dy = -1; dy <= 1; dy += 1) {
				if (!(dx == 0 || dy == 0)) continue;
				if (dx == 0 && dy == 0) continue;
				
				int x = p.x, y = p.y;
				while (node.board.inBoard(x, y)) {
					if (node.board.board[x][y].stone != 0) {
						if (det.filledStones.board[x][y].stone == 0) {
							cnt++;
	//						System.out.println("hit " + x + "," + y);
							break; // found something
						}
					}
					x += dx; y += dy;
				}
			}
		cnt = 4 - cnt; // invert
//		System.out.println("count " + cnt + " for " + Intersection.toGTPloc(p.x, p.y, 19) + " -- " + node.printPath2Here() );
		return cnt;
	}

	// generate paths recursively
	// we can basically be in 2x2 states:
	// on a correct path or not, in computer response or not
	// then we consider both good and bad moves in each scenario
	private void genPathRecurse(KataBrain brain, Node node, BasicGoban probGoban, int depth, boolean onRight, GenOptions gopts, NodeChangeListener ncl) throws Exception {
		node.getRoot().markCrayons();
        ncl.nodeChanged(node);
		boolean isResponse = (depth & 1) == 1; // are we in a computer response move?

		int visits = 1000;
		if (depth == 0) visits = 13000;
		var na = new NodeAnalyzer();
		KataAnalysisResult kar = na.analyzeNode(brain, node, visits, gopts.considerNearDist, gopts.onlyConsiderNear); 
		double baseline = kar.rootInfo.scoreLead;
		System.out.println();
		System.out.println("***> Path: <" + node.printPath2Here() + "> (" + onRight + ")" + ", score: " + baseline + ", total: " + totalVarMoves);
		System.out.println(kar.printMoves());

		// moves from top root analysis
		for (MoveInfo mi: kar.moveInfos) {
//			double score = mi.scoreLead;
//			Point p = Intersection.gtp2point(mi.move);
//			double nearest = findNearest(node.board, p);
			
			if (onRight) {
				handleRightMoveOption(brain, node, probGoban, depth, mi, kar, gopts, ncl);
			} else {
				handleWrongMoveOption(brain, node, probGoban, depth, mi, kar, gopts, ncl);

				//				if (nearest > TENUKI_DIST_ERR) continue;
//				// not a good score, but maybe a good move for the tree
//				double dist = distance2vulnerable(p);
//				if (dist < MAX_VULN_INTEREST) {
//					System.out.println(depth + " >> err: " + mi.move + ", visits: " + mi.visits + ", score: " + score + ", dist vuln: " + dist);
//					// add mistake path
//			        Node tike = node.addBasicMove(p.x, p.y);
//			        if (depth == 0)
//			        	genPathRecurse(engine, tike, probGoban, depth + 1, false);
//				}
			}
		}
		
		// look at moves in primary solution and other hints for starting moves
		if (depth == 0 && firstSol != null) {
			considerPrimaryPath(brain, node, probGoban, gopts, ncl, visits, kar.rootInfo.scoreLead);
			considerOwnershipChangesAsStart(brain, node, probGoban, gopts, ncl, visits, kar.rootInfo.scoreLead);
			considerSolutionNeighbors(brain, node, probGoban, gopts, ncl, visits, kar.rootInfo.scoreLead);
			
			//TODO consider game mistake if nearby
		}
	}

	// look for good starting moves by looking at moves along the primary solution path
	private void considerPrimaryPath(KataBrain brain, Node node, BasicGoban probGoban, GenOptions gopts,
			NodeChangeListener ncl, int visits, double scoreLead) throws Exception {
		System.out.println("first sol: " + firstSol.printPath2Here());
		// as a nice heuristic, let's make sure we consider all moves on this solution path as first move attempts
		var moves = new ArrayList<String>();
		Node n = firstSol;
		while (n != null) {
			MoveAction ma = n.getMoveAction();
			n = n.mom;
			
			if (ma == null) continue;
			Point p = ma.loc;
			// check if in tree already
			if (!node.hasMove(p)) {
				System.out.println("adding move from sol: " + Intersection.toGTPloc(p.x, p.y, 19));
				moves.add(Intersection.toGTPloc(p.x, p.y, 19));
			}
		}
		// what do we have?
		if (moves.size() > 0) {
			var na = new NodeAnalyzer();
			KataAnalysisResult karSol = na.analyzeNode(brain, node, visits, moves); 
			// override baseline because we're missing the best moves
			karSol.rootInfo.scoreLead = scoreLead;
			System.out.println(karSol.printMoves(3));
			for (MoveInfo mi: karSol.moveInfos) {
				System.out.println("---------> " + mi.move);
				handleRightMoveOption(brain, node, probGoban, 0, mi, karSol, gopts, ncl);
			}
		}
	}
	
	// look for good starting moves by looking at places ownership changes
	private void considerSolutionNeighbors(KataBrain brain, Node node, BasicGoban probGoban, GenOptions gopts,
			NodeChangeListener ncl, int visits, double scoreLead) throws Exception {
		var moves = new ArrayList<String>();

		StoneConnect scon = new StoneConnect();
		// first collect all options
        for (Enumeration e = node.babies.elements(); e.hasMoreElements();) {
            Node n = (Node) e.nextElement();
            if (n.searchForTheTruth() == false)
            	continue;
            Point p = n.getMoveAction().loc;
            System.out.println("sol at " + p);
    		ArrayList<Point> neighbs = scon.emptyNeighbors(p, node.board);
    		for (Point pn: neighbs) {
    			// check if in tree already
    			if (node.hasMove(pn)) {
    				continue;
    			}
    			String mv = Intersection.toGTPloc(pn.x, pn.y, 19);
    			System.out.println("sol neighbor: " + mv);
    			moves.add(mv);
    		}
        }
        
		// what do we have?
		if (moves.size() > 0) {
			// TODO: dups??
			var na = new NodeAnalyzer();
			KataAnalysisResult karSol = na.analyzeNode(brain, node, visits, moves);
			// override baseline because we're missing the best moves
			karSol.rootInfo.scoreLead = scoreLead;
			System.out.println(karSol.printMoves(3));
			for (MoveInfo mi: karSol.moveInfos) {
				System.out.println("---------> nbor: " + mi.move);
				handleRightMoveOption(brain, node, probGoban, 0, mi, karSol, gopts, ncl);
			}
		}
	}

	// look for good starting moves by looking at places ownership changes
	private void considerOwnershipChangesAsStart(KataBrain brain, Node node, BasicGoban probGoban, GenOptions gopts,
			NodeChangeListener ncl, int visits, double scoreLead) throws Exception {

		for (Point op: det.fullOwnNeighbors) {
			// check space is empty
			if (node.board.board[op.x][op.y].stone != 0) {
				continue;
			}
			// check if in tree already
			if (node.hasMove(op)) {
				continue;
			}
			var moves = new ArrayList<String>();
			String mv = Intersection.toGTPloc(op.x, op.y, 19);
			moves.add(mv);
			var na = new NodeAnalyzer();
			KataAnalysisResult karSol = na.analyzeNode(brain, node, visits, moves);
			// override baseline because we're missing the best moves
			karSol.rootInfo.scoreLead = scoreLead;
//			System.out.println("own start seq: " + karSol.printMoves());
			for (MoveInfo mi: karSol.moveInfos) {
				System.out.println();
				System.out.println("own >>-------> " + mi.move + ", v: " + mi.visits + ", policy: " + df.format(mi.prior * 1000.0));
				handleRightMoveOption(brain, node, probGoban, 0, mi, karSol, gopts, ncl);
			}
		}
	}

	// return stone ownership change by moving here
	private int calcMoveDelta(KataBrain brain, Node node, Point p, KataAnalysisResult kar, GenOptions gopts) throws Exception {
		// we can verify this by trying a pass
		// first put this move down and measure it directly (existing KAR may have few visits)
		Node tike = node.addBasicMove(p.x, p.y);
		var na = new NodeAnalyzer();
		KataAnalysisResult karMove = na.analyzeNode(brain, tike, MOVE_VISITS_DEF, gopts.considerNearDist, gopts.onlyConsiderNear); 
//		KataAnalysisResult karMove = gopts.onlyConsiderNear ? brain.analyzeNode(tike, MOVE_VISITS_DEF, gopts.considerNearDist) : brain.analyzeNode(tike, MOVE_VISITS_DEF);

		// look at original ownership change stones, see how they differ after this move
		int delta = 0;
		for (Point op: det.ownershipChanges) {
			double od = kar.ownership.get(op.x + op.y * 19) - karMove.ownership.get(op.x + op.y * 19);
//			System.out.println("    md " + op + " : " + od);
			if (Math.abs(od) > MOVE_DELTA_OWNERSHIP_THRESHOLD) {
				delta++;
			}
		}
		System.out.println("  move delta: " + delta + ", " + tike.printPath2Here());

		// clean up
		node.removeChildNode(tike);
		return delta;
	}
	
	// return stone ownership change by passing here
	// this can help determine if a given move is pointless and can be ignored
	private int calcPassDelta(KataBrain brain, Node node, Point p, GenOptions gopts) throws Exception {
		// we can verify this by trying a pass
		System.out.println("starting pass value calculation");
		// first put this move down and measure it directly (existing KAR may have few visits)
		Node tike = node.addBasicMove(p.x, p.y);
		var na = new NodeAnalyzer(Boolean.parseBoolean(props.getProperty("paths.debugpassownership", "false")));
		int baseVisits = Integer.parseInt(props.getProperty("paths.passvisitsbase", "1000"));
		KataAnalysisResult karMove = na.analyzeNode(brain, tike, baseVisits, gopts.considerNearDist, gopts.onlyConsiderNear);

		Node passNode = tike.addBasicMove(19, 19);
		int passVisits = Integer.parseInt(props.getProperty("paths.passvisitspass", "1000"));
		KataAnalysisResult karPass = na.analyzeNode(brain, passNode, passVisits, gopts.considerNearDist, gopts.onlyConsiderNear);
		
		int delta = stoneDelta(karMove, karPass, tike);
		System.out.println("  Pass delta: " + delta + ", " + tike.printPath2Here());

		// clean up
		node.removeChildNode(tike);
		return delta;
	}
	
	// how close is this point to orig ownership change stones?
	private double distance2vulnerable(Point p) {
		double d = 100000;
		for (Point op: det.ownershipChanges) {
			double nd = Math.sqrt((op.x - p.x) * (op.x - p.x) + (op.y - p.y) * (op.y - p.y));
			if (nd < d) d = nd;
		}
		return d;
	}

	// total significant ownership changes
	public int stoneDelta(KataAnalysisResult k1, KataAnalysisResult k2, Node node) {
		int cnt = 0;
		double maxDelta = 0;
		for (int x = 0; x < 19; x++)
			for (int y = 0; y < 19; y++) {
				double od = k1.ownership.get(x + y * 19) - k2.ownership.get(x + y * 19);
				int stn = node.board.board[x][y].stone;
				if (stn == 0) continue;
				maxDelta = Math.max(maxDelta, Math.abs(od));
				if (Math.abs(od) > OWNERSHIP_THRESHOLD) {
//					System.out.println("own delta: " + df.format(od) + ", " + Intersection.toGTPloc(x, y, 19) +
//							" (" + df.format(prev.ownership.get(x + y * 19)) + " -> " + df.format(kar.ownership.get(x + y * 19)) + ")");
					cnt++;
				}
			}
		System.out.println("max move ownership delta: " + maxDelta);
		return cnt;
	}

	// nearest stone to point
	private double findNearest(Board board, Point p) {
		double d = 100000;
		for (int x = 0; x < 19; x++)
			for (int y = 0; y < 19; y++) {
				if (board.board[x][y].stone != Intersection.EMPTY) {
					double nd = Math.sqrt((x - p.x) * (x - p.x) + (y - p.y) * (y - p.y));
					if (nd < d) d = nd;
				}
			}
		return d;
	}

	// create problem branches
	public void makePaths(KataBrain brain, Node problem, BasicGoban probGoban, GenOptions gopts, NodeChangeListener ncl) throws Exception {
		System.out.print("ownership change: ");
		for (Point op: det.ownershipChanges) {
			System.out.print(Intersection.toGTPloc(op.x, op.y, 19));
			System.out.print(" ");
		}
		System.out.println();
		
		genPathRecurse(brain, problem, probGoban, 0, true, gopts, ncl);

		problem.markCrayons();
	}
}
