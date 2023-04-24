package autoprob.katastruct;

import java.text.DecimalFormat;
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
	public List<Double> policy = null;
	
	public String printMoves(int max) {
		StringBuilder sb = new StringBuilder();
		int cnt = 0;
		for (MoveInfo mi: moveInfos) {
			if (cnt++ == max) break;
			sb.append("move: ").append(mi.move).append(", visits: ").append(mi.visits).append(", score: ").append(df.format(mi.scoreLead)).append(", policy: ").append(df.format(mi.prior * 1000.0)).append("\n");
		}
		return sb.toString();
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
}