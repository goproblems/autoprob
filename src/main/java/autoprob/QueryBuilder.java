package autoprob;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;
import autoprob.katastruct.KataQuery;
/*
{
    "id": "foo",
    "initialStones": [
        ["B", "Q4"],
        ["B", "C4"]
    ],
    "moves": [
        ["W", "P5"],
        ["B", "P6"]
    ],
    "rules": "tromp-taylor",
    "komi": 7.5,
    "boardXSize": 19,
    "boardYSize": 19,
    "analyzeTurns": [0, 1, 2]
}
 */

public class QueryBuilder {

	// assumes no branching!
	public KataQuery buildQuery(Node node) {
		var kq = new KataQuery();
		kq.includeOwnership = true;
		kq.includeMovesOwnership = false;
		kq.includeOwnershipStdev = true;
		// Use komi from root node's SGF if available, otherwise default to 7.5
		Node root = node.getRoot();
		kq.komi = getKomi(root);
		int toMove = node.getToMove();
		kq.initialPlayer = Intersection.color2katagoname(toMove);

		// initial stones
		kq.initialStones = new ArrayList<>();
		Board b = node.board;
        for (int i = 0; i < 19; i++)
            for (int j = 0; j < 19; j++) {
                Intersection insec = b.board[i][j];
                if (insec.stone == Intersection.BLACK) {
                	kq.initialStones.add(Arrays.asList("B", Intersection.toGTPloc(i, j, b.boardY)));
                }
                else if (insec.stone == Intersection.WHITE) {
                	kq.initialStones.add(Arrays.asList("W", Intersection.toGTPloc(i, j, b.boardY)));
                }
            }

        // moves
		kq.moves = new ArrayList<>();
		kq.analyzeTurns = new ArrayList<>();
		kq.analyzeTurns.add(0); // 0 is the initial position, so we always analyze that
		Node n = node.favoriteSon(); // skips the move that brought us to this node
        while (n != null) {
        	MoveAction moveAction = n.getMoveAction();
        	if (moveAction != null) {
        		Point loc = moveAction.loc;
        		if (loc.x >= 0) {
//        			if (loc.x == 19) {
////        				break; // pass
//        				kq.moves.add(Arrays.asList(moveAction.stone == Intersection.BLACK ? "B" : "W", Intersection.toGTPloc(loc.x, loc.y, b.boardY)));
//        			}
//        			else {
        				kq.moves.add(Arrays.asList(moveAction.stone == Intersection.BLACK ? "B" : "W", Intersection.toGTPloc(loc.x, loc.y, b.boardY)));
//        			}
        			kq.analyzeTurns.add(kq.analyzeTurns.size());
        		}
        	}
        	n = n.favoriteSon();
        }

		return kq;
	}

	// add single move from mom to us
	public KataQuery buildQueryFromMom(Node node) {
		var kq = new KataQuery();
		kq.includeOwnership = true;
		kq.includeMovesOwnership = false;
		kq.includeOwnershipStdev = true;
		// Use komi from root node's SGF if available, otherwise default to 7.5
		Node root = node.getRoot();
		kq.komi = getKomi(root);
		Node mom = node.mom;
		int toMove = mom.getToMove();
		kq.initialPlayer = Intersection.color2katagoname(toMove);

		// initial stones
		kq.initialStones = new ArrayList<>();
		Board b = mom.board;
        for (int i = 0; i < 19; i++)
            for (int j = 0; j < 19; j++) {
                Intersection insec = b.board[i][j];
                if (insec.stone == Intersection.BLACK) {
                	kq.initialStones.add(Arrays.asList("B", Intersection.toGTPloc(i, j, b.boardY)));
                }
                else if (insec.stone == Intersection.WHITE) {
                	kq.initialStones.add(Arrays.asList("W", Intersection.toGTPloc(i, j, b.boardY)));
                }
            }

        // moves
		kq.moves = new ArrayList<>();
		kq.analyzeTurns = new ArrayList<>();
		kq.analyzeTurns.add(1); // just do after the single move
		MoveAction moveAction = node.getMoveAction();
		Point loc = moveAction.loc;
		kq.moves.add(Arrays.asList(moveAction.stone == Intersection.BLACK ? "B" : "W", Intersection.toGTPloc(loc.x, loc.y, b.boardY)));

		return kq;
	}

	// builds a query for the given node's position, replaying the full move
	// history from the root so that humanSLProfile with ignorePreRootHistory=false
	// sees the sequence of moves that led to this position
	public KataQuery buildQueryWithHistory(Node node) {
		Node root = node.getRoot();
		var kq = new KataQuery();
		kq.includeOwnership = false;
		kq.includeMovesOwnership = false;
		kq.includeOwnershipStdev = false;
		// Use komi from root node's SGF if available, otherwise default to 7.5
		kq.komi = getKomi(root);
		int toMove = root.getToMove();
		kq.initialPlayer = Intersection.color2katagoname(toMove);

		// initial stones: the base position, without any joseki path moves
		kq.initialStones = new ArrayList<>();
		Board b = root.board;
		for (int i = 0; i < 19; i++) {
			for (int j = 0; j < 19; j++) {
				Intersection insec = b.board[i][j];
				if (insec.stone == Intersection.BLACK) {
					kq.initialStones.add(Arrays.asList("B", Intersection.toGTPloc(i, j, b.boardY)));
				} else if (insec.stone == Intersection.WHITE) {
					kq.initialStones.add(Arrays.asList("W", Intersection.toGTPloc(i, j, b.boardY)));
				}
			}
		}

		// moves: every move from the root down to the given node, in order
		kq.moves = new ArrayList<>();
		List<Node> path = new ArrayList<>();
		Node n = node;
		while (n != root) {
			path.add(n);
			n = n.mom;
		}
		Collections.reverse(path);
		for (Node pathNode : path) {
			MoveAction moveAction = pathNode.getMoveAction();
			if (moveAction == null) {
				continue;
			}
			Point loc = moveAction.loc;
			if (loc.x == root.board.boardX || loc.y == root.board.boardY) {
				kq.moves.add(Arrays.asList(
					moveAction.stone == Intersection.BLACK ? "B" : "W",
					"pass"
				));
			} else if (loc.x >= 0 && loc.y >= 0) {
				kq.moves.add(Arrays.asList(
					moveAction.stone == Intersection.BLACK ? "B" : "W",
					Intersection.toGTPloc(loc.x, loc.y, root.board.boardY)
				));
			}
		}

		// analyze only the final position (the node's position)
		kq.analyzeTurns = new ArrayList<>();
		kq.analyzeTurns.add(kq.moves.size());
		return kq;
	}

	/**
	 * Get komi value from root node's SGF, default to 7.5
	 *
	 * @param root Root node to extract komi from
	 * @return Komi value
	 */
	private double getKomi(Node root) {
		try {
			String komiStr = root.getXtra("KM");
			return (komiStr != null) ? Double.valueOf(komiStr) : 7.5;
		} catch (NumberFormatException e) {
			return 7.5;
		}
	}
}
