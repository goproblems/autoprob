package autoprob.katastruct;

import java.awt.*;
import java.util.List;

import autoprob.go.Intersection;
import autoprob.go.Node;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

public class KataQuery {

	@SerializedName("id")
	@Expose
	public String id;
	@SerializedName("initialStones")
	@Expose
	public List<List<String>> initialStones = null;
	@SerializedName("moves")
	@Expose
	public List<List<String>> moves = null;

	@SerializedName("rules")
	@Expose
	public String rules = "tromp-taylor";

	@SerializedName("initialPlayer")
	@Expose
	public String initialPlayer = "B";
	
	@SerializedName("komi")
	@Expose
	public Double komi;
	
	public Boolean includeOwnership;
	public Boolean includeOwnershipStdev;
	public Boolean includeMovesOwnership;
	public Boolean includePolicy;

	@SerializedName("maxVisits")
	@Expose
	public Integer maxVisits;

	@SerializedName("boardXSize")
	@Expose
	public Integer boardXSize = 19;

	@SerializedName("boardYSize")
	@Expose
	public Integer boardYSize = 19;
	
	@SerializedName("analyzeTurns")
	@Expose
	public List<Integer> analyzeTurns = null;

	@SerializedName("allowMoves")
	@Expose
	public List<AllowMove> allowMoves = null;

    public Node generateNode() {
		Node n = new Node(null);
		// set board from initialStones
		for (List<String> stone : initialStones) {
			String color = stone.get(0);
			String loc = stone.get(1);
			Point p = Intersection.gtp2point(loc);
			if (color.equals("B")) {
				n.board.board[p.x][p.y].stone = Intersection.BLACK;
			}
			else {
				n.board.board[p.x][p.y].stone = Intersection.WHITE;
			}
		}
		// add any moves
		if (moves != null) {
			for (List<String> move : moves) {
				String color = move.get(0);
				String loc = move.get(1);
				Point p = Intersection.gtp2point(loc);
				if (color.equals("B")) {
					n.board.board[p.x][p.y].stone = Intersection.BLACK;
				} else {
					n.board.board[p.x][p.y].stone = Intersection.WHITE;
				}
			}
		}

		return n;
    }

	public String printMoves() {
		String s = "";
		if (moves != null) {
			for (List<String> move : moves) {
				s += move.get(0) + " " + move.get(1) + "\n";
			}
		}
		return s;
	}
}