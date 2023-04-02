package autoprob;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;

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
		int toMove = node.getToMove();
		if (toMove == Intersection.BLACK)
			kq.initialPlayer = "B";
		else
			kq.initialPlayer = "W";
		
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
		Node mom = node.mom;
		int toMove = mom.getToMove();
		if (toMove == Intersection.BLACK)
			kq.initialPlayer = "B";
		else
			kq.initialPlayer = "W";
		
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
}
