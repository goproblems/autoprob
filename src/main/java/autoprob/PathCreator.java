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
    protected static final DecimalFormat df = new DecimalFormat("0.00");
	protected final boolean debugOwnership;
	protected final KataBrain brain;
	protected final int ignoreResponseVisitsDepth; // normally we do responses if they get enough visits, even if the policy is low but setting this will cap it out -- otherwise variations go almost forever
	protected boolean abortNow = false;
	protected BasicGoban probGoban;
	protected GenOptions gopts;
	protected NodeChangeListener ncl;

	// signal to stop exploring new things, exit gracefully
	public void abortPathCreation() {
		abortNow = true;
    }

    // generation options
	public class GenOptions {
		public boolean altRefutes = false;
		public boolean altChallenges = false; // alternative ways to test human on correct line
		public boolean onlyConsiderNear = true; // tell kata to only look at possible moves near existing stones
		public double considerNearDist = 2.1; // how close for near moves
		public int pathsVisits = 1000;
		public int maxDepth = 10000;

		@Override
		public String toString() {
			return "GenOptions [altRefutes=" + altRefutes + ", altChallenges=" + altChallenges + ", onlyConsiderNear="
					+ onlyConsiderNear + ", considerNearDist=" + considerNearDist + ", pathsVisits=" + pathsVisits
					+ ", maxDepth=" + maxDepth + "]";
		}
	}

	public static final int MIN_VISITS_WRONG = 10; // consider wrong moves with this many visits
	public static final double TENUKI_DIST = 2.7; // a move this far away from any other is a tenuki
	public static final double TENUKI_DIST_ERR = 2.5; // a move this far away from any other is a tenuki
	public static final double OWNERSHIP_THRESHOLD = 1.1;
	public static double MOVE_DELTA_OWNERSHIP_THRESHOLD = 0.8; // how moves affect ownership
	public static int MIN_OWNERSHIP_CHANGE_INTEREST = 4; // max deviation from detector ownership
	public static final int MIN_GOOD_RESPONSE_VISITS = 5;
	public static final double MIN_GOOD_RESPONSE_POLICY = 0.05;
	public static final double MIN_GOOD_DEEP_RESPONSE_VISIT_RATIO = 0.5;
	public static final double MAX_VULN_INTEREST = 2.1; // 
	public int bailNum = 40; // max moves in tree
	public int bailDepth = 2; // only bail if at least this deep

	protected int totalVarMoves = 0; // how many have been added to tree
	protected Node firstSol = null; // first solution we find
	protected final ProblemDetector det;
	protected final Properties props;
	protected int[] minPolicies;

	public PathCreator(ProblemDetector det, Properties props, KataBrain brain) {
		this.det = det;
		this.props = props;
		this.brain = brain;
//		this.maxDepth = Integer.parseInt(props.getProperty("paths.max_depth", "10000"));
		MIN_OWNERSHIP_CHANGE_INTEREST = Integer.parseInt(props.getProperty("paths.life_mistake_stones", "4"));
		MOVE_DELTA_OWNERSHIP_THRESHOLD = Double.parseDouble(props.getProperty("paths.life_mistake_threshold", "0.8"));

		this.debugOwnership = Boolean.parseBoolean(props.getProperty("paths.debug_ownership", "false"));
		bailDepth = Integer.parseInt(props.getProperty("paths.bail_depth", "4"));
		bailNum = Integer.parseInt(props.getProperty("paths.bail_number", "40000"));
		parseMinPolicyPrefs();
		this.ignoreResponseVisitsDepth = Integer.parseInt(props.getProperty("paths.ignore_response_visits_depth", "8"));
	}

	// we specify minimum policy with a comma separated list
	protected void parseMinPolicyPrefs() {
		String s = props.getProperty("paths.min_policy");
		String[] split = s.split(",");
		minPolicies = new int[split.length];
		// convert to integers
		for (int i = 0; i < split.length; i++) {
			split[i] = split[i].trim();
			minPolicies[i] = Integer.parseInt(split[i]);
		}
	}

	// get min policy for a given depth
	protected int getMinPolicy(int depth) {
		if (depth >= minPolicies.length) return minPolicies[minPolicies.length - 1];
		return minPolicies[depth];
	}

	// checks policy and sometimes visits
	protected boolean interestingLookingMove(MoveInfo mi, int depth, boolean considerResponseDepth) {
		int visits = gopts.pathsVisits;

		int minPolicy = getMinPolicy(depth);
		boolean interesting = false;

		if (mi.prior > ((double)minPolicy / 1000.0))
			interesting = true;
		if (mi.visits > visits * 0.5) {
			if (considerResponseDepth && depth >= ignoreResponseVisitsDepth) {
				// ignore it
			} else {
				interesting = true;
			}
		}
		return interesting;
	}

	protected boolean interestingLookingMove(MoveInfo mi, int depth) {
		return interestingLookingMove(mi, depth, false);
	}

		// RIGHT: we are in a correct variation -- no errors by human
	protected void handleRightMoveOption(Node nodeParent, int depth, MoveInfo mi, KataAnalysisResult kar) throws Exception {
		if (abortNow) return; // early exit

		boolean isResponse = (depth & 1) == 1; // are we in a computer response move?
		Point p = Intersection.gtp2point(mi.move);
		double nearest = findNearest(nodeParent.board, p);

		// for right sequences, we END with a human move
		// we always respond to a good human move
		// try to add good human moves to the tree
		// there must always be at least one correct human move. there may be mistakes we add too if they look interesting.
		
		if (depth == 0) System.out.println();

		// human or computer move?
		if (isResponse) {
			// COMPUTER
			boolean goodMove = true; // should calc on something? used to be Math.abs(baseline - score) < EXTRA_SOLUTION_THRESHOLD
			if (goodMove) {
				// good response relative to the situation
				// but we have to check if it's interesting to add to the tree -- if it challenges the human in a way that makes sense
				// not necessary to add this depending on situation

				// if too far from other moves, not interesting
				if (nearest > TENUKI_DIST_ERR) return;
				
				System.out.println("  _considering_ response: " + nodeParent.printPath2Here() + ", " + mi.extString());

				if (mi.visits < MIN_GOOD_RESPONSE_VISITS && mi.prior < MIN_GOOD_RESPONSE_POLICY) {
					System.out.println("  bad visits and policy");
					return;
				}
				if (firstSol != null && nodeParent.depth > firstSol.depth) {
					// check vs first sol depth, don't exceed too much
					if (mi.visits < MIN_GOOD_DEEP_RESPONSE_VISIT_RATIO * kar.rootInfo.visits) {
						System.out.println("  too deep");
						return;
					}
				}
				if (depth >= gopts.maxDepth) {
					System.out.println("(computer response) reached max depth as specified: " + gopts.maxDepth);
					return;
				}

				if (!gopts.altRefutes && nodeParent.babies.size() > 0) return; // already handled this var (we already refuted)
				
				// if this is not an intuitive move, don't bother
				if (!interestingLookingMove(mi, depth, true)) {
					System.out.println("  low prior " + mi.extString() + " depth " + depth);
					return;
				}
				
				// it's only interesting if it threatens to change life status
				int delta = calcPassDelta(nodeParent, p, gopts);
		        if (delta < MIN_OWNERSHIP_CHANGE_INTEREST) {
		        	System.out.println("  response rejected, no threat: " + delta);
		        	return; // no threat, so don't add to tree
		        }

				// okay we have decided it's interesting, let's add the response and challenge the human
				System.out.println("  interesting response: " + nodeParent.printPath2Here() + ", " + mi.extString());
		        Node tike = nodeParent.addBasicMove(p.x, p.y);
		        // recurse for responses
		        genPathRecurse(tike, depth + 1, true);
			} else {
				// bad move -- ignore
			}
		} else {
			// HUMAN
			// see if we have kept the relevant alive state -- we know we are still comparing against original detection because we're in a right variation
			int delta = calcMoveDelta(nodeParent, p, kar, gopts);
			boolean significantOwnershipChange = (delta > MIN_OWNERSHIP_CHANGE_INTEREST);

			if (!significantOwnershipChange) {
				// they are making a correct move
				System.out.println("  d:" + depth + " >> human sol: " + mi.extString() + ", nearest: " + df.format(nearest) + ", path: " + nodeParent.printPath2Here());

				// check move isn't a tenuki
				if (nearest > TENUKI_DIST) {
					return;
				}

				// add to tree
				Node tike = nodeParent.addBasicMove(p.x, p.y);
		        tike.result = Intersection.RIGHT;

				// track first solution we find
		        if (firstSol == null)
		        	firstSol = tike;
		        if (depth > 1) {
		        	nodeParent.mom.result = 0; // this becomes not a solution
		        	if (firstSol == nodeParent.mom)
		        		firstSol = tike; // update to deeper in tree
		        }

		        if (totalVarMoves++ > bailNum && depth > bailDepth) {
					System.out.println("$$$$$$$$$ human solve bail");
					return;
				}
				if (depth >= gopts.maxDepth) {
					System.out.println("(human good move) reached max depth as specified: " + gopts.maxDepth);
					return;
				}

		        // recurse for responses
		        genPathRecurse(tike, depth + 1, true);
			} else {
				// bad human move
				System.out.println("  _considering_ new mistake: " + nodeParent.printPath2Here() + ", " + mi.extString());
				
				if (nearest > TENUKI_DIST_ERR) {
					System.out.println("tenuki");
					return; // if too far from other moves, not interesting
				}

				if (mi.visits < MIN_VISITS_WRONG && mi.prior < MIN_GOOD_RESPONSE_POLICY) {
					if (depth > 0) {
			        	System.out.println("  too few visits and policy: " + mi.extString());
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
				
				if (!interestingLookingMove(mi, depth)) {
					System.out.println("  low prior " + mi.extString() + " depth " + depth);
					return;
				}

				if (countEmptyShots(p, nodeParent) >= 3 && !det.fullOwnershipChanges.contains(p))
					return; // looks on outside
				if (depth >= gopts.maxDepth) {
					System.out.println("(human mistake) reached max depth as specified: " + gopts.maxDepth);
					return;
				}

				// it's only interesting if it threatens to change life status, as in the opponent at least needs to respond to this move
				int passDelta = calcPassDelta(nodeParent, p, gopts);
		        if (passDelta < MIN_OWNERSHIP_CHANGE_INTEREST) {
		        	System.out.println("  mistake has bad pass ownership change: " + mi.move + ", delta: " + passDelta);
		        	return; // no threat, so don't add to tree
		        }
		        
		        // this human move should be answered
		        System.out.println("  will respond to mistake: " + nodeParent.printPath2Here() + ", " + mi.extString());
		        // recurse
		        Node tike = nodeParent.addBasicMove(p.x, p.y);
		        genPathRecurse(tike, depth + 1, false);
			}
		}
	}

	// WRONG: we are in a wrong variation -- one or more human errors
	protected void handleWrongMoveOption(Node node, int depth, MoveInfo mi,
			KataAnalysisResult karParent) throws Exception {
		if (abortNow) return; // early exit

		boolean isResponse = (depth & 1) == 1; // are we in a computer response move?
		Point p = Intersection.gtp2point(mi.move);
		double nearest = findNearest(node.board, p);

		// for wrong sequences, we END with a computer move, which is the refutation
		// human or computer move?
		if (isResponse) {
			// computer
			if (!gopts.altRefutes && node.babies.size() > 0) return; // already handled this var
			// get more visits on this move
			var karMove = calcMoveAnalysis(node, p, gopts);
			int ownershipDelta = stoneDelta(karParent, karMove, node);
			// note, a move can only make ownership worse, since it was already assumed to have perfect responses

			if (ownershipDelta < MIN_OWNERSHIP_CHANGE_INTEREST) {
				// good response relative to the situation
				// necessary to add so we refute
				//TODO maybe only add if not already a good refutation. eventually, figure out best refutation. or possibly make them choices
				
				// if too far from other moves, not interesting
				if (nearest > TENUKI_DIST) {
					System.out.println("WARNING: oddly distant refutation move " + mi.move);
				}

				// okay we have decided it's interesting, let's add the response and challenge the human
				System.out.println("  refutation: " + mi.move + ", visits: " + mi.visits + ", " + node.printPath2Here());
		        Node tike = node.addBasicMove(p.x, p.y);
		        // recurse for responses
		        genPathRecurse(tike, depth + 1, false);
			} else {
				// bad move -- ignore
			}
		} else {
			// human is trying this

			// check if it's relevant
			System.out.println("  _considering_ human wrong path attempt: " + node.printPath2Here() + ", " + mi.extString());

			double dist = distance2vulnerable(p);
			if (dist > MAX_VULN_INTEREST) {
//					System.out.println("  too dist to vuln: " + dist);
				return;
			}
			if (nearest > TENUKI_DIST_ERR) {
				System.out.println("  tenuki: " + nearest);
				return;
			}
			if (countEmptyShots(p, node) >= 3 && !det.fullOwnershipChanges.contains(p))
				return; // looks on outside

			if (!interestingLookingMove(mi, depth)) {
				System.out.println("  low prior " + mi.extString() + " depth " + depth);
				return;
			}

			if (totalVarMoves++ > bailNum && depth > bailDepth) {
				System.out.println("$$$$$$$$$$$$$$ bail wrong");
				return;
			}
			if (depth >= gopts.maxDepth) {
				System.out.println("(human mistake in wrong) reached max depth as specified: " + gopts.maxDepth);
				return;
			}

			// get more visits on this move
			var karMove = calcMoveAnalysis(node, p, gopts);
			int ownershipDelta = stoneDelta(karParent, karMove, node);

			// note, a move can only make ownership worse, since it was already assumed to have perfect responses
			if (ownershipDelta < MIN_OWNERSHIP_CHANGE_INTEREST) {
				// good response relative to the situation

				// it's only interesting if it threatens to change life status
				int delta = calcPassDelta(node, p, gopts);
		        if (delta < MIN_OWNERSHIP_CHANGE_INTEREST) {
		        	return; // no threat, so don't add to tree
		        }
				
		        System.out.println("  human wrong path attempt: " + node.printPath2Here() + ", " + mi.extString());
				Node tike = node.addBasicMove(p.x, p.y);
		        // recurse for responses
		        genPathRecurse(tike, depth + 1, false);
			} else {
				// bad move in a bad var
				//TODO: still consider if policy very high
			}
		}
	}

	// how many directions can we shoot a line off the board unimpeded?
	// approximation for empty space
	protected int countEmptyShots(Point p, Node node) {
		int cnt = 0;

		// count edges specially -- add one if on edge
		if (p.x == 0 || p.y == 0 || p.x == 18 || p.y == 18)
			cnt = 1;
		
		for (int dx = -1; dx <= 1; dx += 1)
			for (int dy = -1; dy <= 1; dy += 1) {
				if (!(dx == 0 || dy == 0)) continue;
				if (dx == 0 && dy == 0) continue;
				
				int x = p.x, y = p.y;
				// add one if we find a stone
				while (node.board.inBoard(x, y)) {
					if (node.board.board[x][y].stone != 0) {
						if (det.filledStones.board[x][y].stone == 0) {
							cnt++;
//							System.out.println("empty shots hit " + x + "," + y);
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
	protected void genPathRecurse(Node node, int depth, boolean onRight) throws Exception {
		node.getRoot().markCrayons();
        ncl.nodeChanged(node);
		boolean isResponse = (depth & 1) == 1; // are we in a computer response move?

		if (abortNow) return; // early exit

		int visits = gopts.pathsVisits;
		if (depth == 0) visits = Integer.parseInt(props.getProperty("paths.visits_root"));
		var na = new NodeAnalyzer(props, debugOwnership);
		KataAnalysisResult kar = na.analyzeNode(brain, node, visits, gopts.considerNearDist, gopts.onlyConsiderNear, det.filledStones);
		node.kres = kar; // save for debugging
		System.out.println();
		System.out.println("***> Path: <" + node.printPath2Here() + "> (" + onRight + ")" + ", total: " + totalVarMoves);
		int movePrintMax = Integer.parseInt(props.getProperty("paths.debug_print_moves", "0"));
		System.out.println(kar.printMoves(movePrintMax));

		// moves from top root analysis (root of this branch, not necessarily root of tree)
		for (MoveInfo mi: kar.moveInfos) {
			//TODO: analyze this with more visits
			// skip moves if in forbidden list
			if (depth == 0 && !isAllowedRootMove(mi.move)) {
				System.out.println("  skip not allowed: " + mi.move);
				continue;
			}

			if (onRight) {
				handleRightMoveOption(node, depth, mi, kar);
			} else {
				handleWrongMoveOption(node, depth, mi, kar);
			}
		}
		
		// look at moves in primary solution and other hints for starting moves
		if (depth == 0 && firstSol != null) {
			considerPrimaryPathAsStart(node, kar);
			considerOwnershipChangesAsStart(node, kar);
			considerSolutionNeighborsAsStart(node, kar);
			
			//TODO consider game mistake if nearby
		}
	}

	// user can specify allowed and forbidden moves from the command line
	protected boolean isAllowedRootMove(String move) {
		String onlyTry = props.getProperty("paths.only_try_moves", "");
		if (onlyTry.length() > 0) {
			if (onlyTry.contains(move)) {
				return true;
			}
			return false;
		}
		return true;
	}

	// given a potential starting move, analyze it and recurse appropriately
	// auto adds this to tree to start
	protected boolean analyzeAndStart(Node node, Point p, KataAnalysisResult karParent) throws Exception {
		if (abortNow) return false; // early exit
		String mv = Intersection.toGTPloc(p.x, p.y);
		if (!isAllowedRootMove(mv)) return false;
		Node tike = node.addBasicMove(p.x, p.y);
		var na = new NodeAnalyzer(props);
		int visits = Integer.parseInt(props.getProperty("paths.visits_root"));
		KataAnalysisResult karMove = na.analyzeNode(brain, tike, visits);

		// decide if this is a good or bad move
		int ownershipDelta = stoneDelta(karParent, karMove, node);
		boolean onRight = (ownershipDelta < MIN_OWNERSHIP_CHANGE_INTEREST);
		// recurse
		genPathRecurse(tike, 1, onRight);

		return true;
	}

	// look for good starting moves by looking at moves along the primary solution path
	// theory is changing move order is always something to consider, for both right and wrong
	protected void considerPrimaryPathAsStart(Node node, KataAnalysisResult kar) throws Exception {
		System.out.println("first sol: " + firstSol.printPath2Here());
		// as a nice heuristic, let's make sure we consider all moves on this solution path as first move attempts
		Node n = firstSol;
		while (n != null) {
			MoveAction ma = n.getMoveAction();
			n = n.mom;
			
			if (ma == null) continue;
			Point p = ma.loc;
			if (!node.legalMove(p))
				continue;

			// check if in tree already
			if (!node.hasMove(p)) {
				String mv = Intersection.toGTPloc(p.x, p.y);
				System.out.println("adding move from sol: " + mv);
				analyzeAndStart(node, p, kar);
			}
		}
	}
	
	// look for good starting moves next to known solutions
	protected void considerSolutionNeighborsAsStart(Node node, KataAnalysisResult kar) throws Exception {
		StoneConnect scon = new StoneConnect();
		// first collect all options
        for (Enumeration e = node.babies.elements(); e.hasMoreElements();) {
			if (abortNow) return; // early exit
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
				if (!node.legalMove(pn))
					continue;
    			String mv = Intersection.toGTPloc(pn.x, pn.y, 19);
    			System.out.println("sol neighbor: " + mv);
				if (!isAllowedRootMove(mv)) continue;
				analyzeAndStart(node, p, kar);
    		}
        }
	}

	// look for good starting moves by looking at places ownership changes
	protected void considerOwnershipChangesAsStart(Node node, KataAnalysisResult kar) throws Exception {
		for (Point op: det.fullOwnNeighbors) {
			if (abortNow) return; // early exit
			// check space is empty
			if (node.board.board[op.x][op.y].stone != 0) {
				continue;
			}
			// check if in tree already
			if (node.hasMove(op)) {
				continue;
			}
			if (!node.legalMove(op))
				continue;
			var moves = new ArrayList<String>();
			String mv = Intersection.toGTPloc(op.x, op.y);
			System.out.println("own change start >>-------> " + mv);
			analyzeAndStart(node, op, kar);
		}
	}

	// calculate KAR for a move with katago
	protected KataAnalysisResult calcMoveAnalysis(Node node, Point p, GenOptions gopts) throws Exception {
		// first put this move down and measure it directly (existing KAR may have few visits)
		Node tike = node.addBasicMove(p.x, p.y);
		var na = new NodeAnalyzer(props, debugOwnership);
		int visits = gopts.pathsVisits;
		KataAnalysisResult karMove = na.analyzeNode(brain, tike, visits, gopts.considerNearDist, gopts.onlyConsiderNear, det.filledStones);

		// clean up
		node.removeChildNode(tike);
		return karMove;
	}

	// return stone ownership change by moving here, the delta between this and the original ownership change from the detector
	protected int calcMoveDelta(Node node, Point p, KataAnalysisResult kar, GenOptions gopts) throws Exception {
		// first put this move down and measure it directly (existing KAR may have few visits)
		KataAnalysisResult karMove = calcMoveAnalysis(node, p, gopts);

		// look at original ownership change stones, see how they differ after this move
		int delta = 0;
		for (Point op: det.ownershipChanges) {
			double od = kar.ownership.get(op.x + op.y * 19) - karMove.ownership.get(op.x + op.y * 19);
			if (debugOwnership) {
				String mv = Intersection.toGTPloc(op.x, op.y);
				System.out.println("    stone " + mv + " : " + df.format(od));
			}
			if (Math.abs(od) > MOVE_DELTA_OWNERSHIP_THRESHOLD) {
				delta++;
			}
		}
		System.out.println("  move delta vs detector: " + delta + ", " + node.printPath2Here() + ", " + p);

		return delta;
	}
	
	// return stone ownership change by passing here
	// this can help determine if a given move is pointless and can be ignored
	protected int calcPassDelta(Node node, Point p, GenOptions gopts) throws Exception {
		// we can verify this by trying a pass
		int baseVisits = Integer.parseInt(props.getProperty("paths.pass_visits_base", "1000"));
		int passVisits = Integer.parseInt(props.getProperty("paths.pass_visits_pass", "1000"));
		System.out.println("starting pass value calculation, base visits: " + baseVisits + ", pass visits: " + passVisits);
		// first put this move down and measure it directly (existing KAR may have few visits)
		Node tike = node.addBasicMove(p.x, p.y);
		var na = new NodeAnalyzer(props, debugOwnership);
		KataAnalysisResult karMove = na.analyzeNode(brain, tike, baseVisits, gopts.considerNearDist, gopts.onlyConsiderNear, det.filledStones);

		Node passNode = tike.addBasicMove(19, 19);
		KataAnalysisResult karPass = na.analyzeNode(brain, passNode, passVisits, gopts.considerNearDist, gopts.onlyConsiderNear, det.filledStones);
		
		int delta = stoneDelta(karMove, karPass, tike);
		System.out.println("  Pass delta stones: " + delta + ", " + tike.printPath2Here());

		// clean up
		node.removeChildNode(tike);
		return delta;
	}
	
	// how close is this point to orig ownership change stones?
	protected double distance2vulnerable(Point p) {
		double d = 100000;
		for (Point op: det.ownershipChanges) {
			double nd = Math.sqrt((op.x - p.x) * (op.x - p.x) + (op.y - p.y) * (op.y - p.y));
			if (nd < d) d = nd;
		}
		return d;
	}

	// total significant ownership changes
	public int stoneDelta(KataAnalysisResult k1, KataAnalysisResult k2, Node childNode) {
		int cnt = 0;
		double maxDelta = 0;
		for (int x = 0; x < 19; x++)
			for (int y = 0; y < 19; y++) {
				double od = k1.ownership.get(x + y * 19) - k2.ownership.get(x + y * 19);
				int stn = childNode.board.board[x][y].stone;
				if (stn == 0) continue;
				maxDelta = Math.max(maxDelta, Math.abs(od));
				if (Math.abs(od) > OWNERSHIP_THRESHOLD) {
//					System.out.println("own delta: " + df.format(od) + ", " + Intersection.toGTPloc(x, y, 19) +
//							" (" + df.format(prev.ownership.get(x + y * 19)) + " -> " + df.format(kar.ownership.get(x + y * 19)) + ")");
					cnt++;
				}
			}
		System.out.println("max move ownership delta: " + df.format(maxDelta));
		return cnt;
	}

	// nearest stone to point
	protected double findNearest(Board board, Point p) {
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
	public void makePaths(Node problem, BasicGoban probGoban, GenOptions gopts, NodeChangeListener ncl) throws Exception {
		this.probGoban = probGoban;
		this.gopts = gopts;
		this.ncl = ncl;

		System.out.print("ownership change: ");
		for (Point op: det.ownershipChanges) {
			System.out.print(Intersection.toGTPloc(op.x, op.y, 19));
			System.out.print(" ");
		}
		System.out.println();
		
		genPathRecurse(problem, 0, true);

		problem.markCrayons();
	}
}
