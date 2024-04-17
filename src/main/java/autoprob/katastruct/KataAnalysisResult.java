package autoprob.katastruct;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;

import autoprob.go.Intersection;
import autoprob.go.Node;

/** https://www.jsonschema2pojo.org/ */

public class KataAnalysisResult {
	public static final double OWN_THRESH = 0.5;
    private static final DecimalFormat df = new DecimalFormat("0.00");

	@SerializedName("id")
	@Expose
	public String id;
	@SerializedName("isDuringSearch")
	@Expose
	public Boolean isDuringSearch;
	@SerializedName("moveInfos")
	@Expose
	public List<MoveInfo> moveInfos = null;
	@SerializedName("rootInfo")
	@Expose
	public RootInfo rootInfo;
	@SerializedName("turnNumber")
	@Expose
	public Integer turnNumber;

	public List<Double> ownership = null;
	public List<Double> ownershipStdev = null;
	public List<Double> policy = null;

	public static class Policy {
		public double policy;
		public int x;
        public int y;
	}
	
	public String printMoves(int max) {
		StringBuilder sb = new StringBuilder();
		int cnt = 0;
		for (MoveInfo mi: moveInfos) {
			if (cnt++ == max) break;
			sb.append("kar move: ").append(mi.move).append(", visits: ").append(mi.visits).append(", score: ").
					append(df.format(mi.scoreLead)).append(", policy: ").append(df.format(mi.prior * 1000.0)).
					append(", order: ").append(mi.order).
					append("\n");
		}
		return sb.toString();
	}

	@Override
	public String toString() {
		return printMoves(3);
	}

	public String printMoves() {
		return printMoves(10000);
	}

	// print a graphical representation of owned stones
	public void drawOwnership(Node node) {
		if (ownership == null) return;
		for (int y = 0; y < 19; y++) {
			for (int x = 0; x < 19; x++) {
				if (node.board.board[x][y].stone != Intersection.EMPTY) {
					double o = ownership.get(x + y * 19);
					if (o > OWN_THRESH)
						System.out.print('b');
					else if (o < -OWN_THRESH)
						System.out.print('w');
					else
						System.out.print('-');
				}
				else
					System.out.print('.');
			}
			System.out.println();
		}
		System.out.println();
	}

	// print a graphical representation of owned stones, with a numeral
	public void drawNumericalOwnership(Node node) {
		if (ownership == null) return;
		for (int y = 0; y < 19; y++) {
			for (int x = 0; x < 19; x++) {
				if (node.board.board[x][y].stone != Intersection.EMPTY) {
					double o = ownership.get(x + y * 19);
					if (o > 0) {
						int d = (int) (o * 9.99);
						// print padded int to 2 digits
						System.out.print(' ');
						System.out.print(d);
					}
					else if (o < 0) {
						int d = (int) (o * 9.99);
						// print padded int to 2 digits
						if (d == 0) System.out.print(' ');
						System.out.print(d);
					}
					else
						System.out.print('-');
				}
				else
					System.out.print(" .");
				System.out.print(' ');
			}
			System.out.println();
		}
		System.out.println();
	}

	// print a graphical representation
	public void drawPolicy(Node node) {
		if (policy == null) return;
		for (int y = 0; y < 19; y++) {
			for (int x = 0; x < 19; x++) {
				if (node.board.board[x][y].stone == Intersection.EMPTY) {
					double o = policy.get(x + y * 19);
					if (o > 0.01) {
						int d = (int) (o * 99.99);
						// print padded int to 2 digits
						if (d < 10)
							System.out.print(' ');
						System.out.print(d);
					}
					else
						System.out.print(" -");
				}
				else {
					System.out.print(' ');
					System.out.print(node.board.board[x][y].stone == Intersection.BLACK ? 'X' : '@');
				}
			}
			System.out.println();
		}
		System.out.println();
	}

	// returns top num policy moves with their board locations
	public List<Policy> getTopPolicy(int num, List<Point> moves, boolean includeMoves) {
		List<Policy> top = new ArrayList<>();
		// add all policy locations to list, then sort
		for (int y = 0; y < 19; y++) {
			for (int x = 0; x < 19; x++) {
				Policy p = new Policy();
				p.policy = policy.get(x + y * 19);
				p.x = x;
				p.y = y;
				if (includeMoves) {
					if (moves.contains(new Point(p.x, p.y))) {
						top.add(p);
					}
				} else {
					if (!moves.contains(new Point(p.x, p.y))) {
						top.add(p);
					}
				}
			}
		}
		top.sort((a, b) -> Double.compare(b.policy, a.policy));
		// truncate to num
		if (top.size() > num) top = top.subList(0, num);
		return top;
	}

	public MoveInfo getMoveInfo(String loc) {
		for (MoveInfo mi: moveInfos) {
			if (mi.move.equals(loc)) return mi;
		}
		return null;
	}
}