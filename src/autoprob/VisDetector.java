package autoprob;

import java.awt.Color;
import java.awt.Point;
import java.text.DecimalFormat;
import java.util.Properties;

import javax.swing.JLabel;

import autoprob.go.Intersection;
import autoprob.katastruct.MoveInfo;
import autoprob.vis.PosFrame;

// creates a visible frame to show a detected problem
public class VisDetector {
    private static final DecimalFormat df = new DecimalFormat("0.00");
    private static ProblemDetector prevDetection;
	private Properties props;

	public VisDetector(Properties props) {
		this.props = props;
	}

	public boolean newDetection(KataBrain brain, ProblemDetector det, String fileName) {
		// check dup from previous
		if (prevDetection != null) {
			// compare
			int delta = det.node.board.deltaBoard(prevDetection.node.board);
			System.out.println("* detection delta: " + delta);
			if (delta <= 1) {
				System.out.println("* skipping duplicate problem");
				return false;
			}
		}
		
		// hack to avoid more dups, don't repeat solve coords
		if (prevDetection != null) {
			if (prevDetection.solString.equals(det.solString)) {
				System.out.println("* skipping dup sol string: " + det.solString);
				return false;
			}
		}
		
		prevDetection = det;
		
		double baseScore = det.prev.rootInfo.scoreLead; // represents best
		// decorate source board
		// put other best moves on the board
		for (MoveInfo mi: det.prev.moveInfos) {
			String spos = mi.move;
			Point p = Intersection.gtp2point(spos);
			
			double scoreDiff = Math.abs(baseScore - mi.scoreLead); // could be either direction depending on whose move it is
			double mx = 15;
			System.out.println("move: " + mi.move + ", diff: " + df.format(scoreDiff) + ", visits: " + mi.visits);
			scoreDiff = Math.min(scoreDiff, mx); // cap visual
			int green = 100 + (int)(150 * (1.0 - scoreDiff / mx));
			green = Math.min(green, 255);
			Color c = new Color(80, green, 80);
			
			if (p.x == 19 || p.y == 19)
				continue; // pass
			det.node.board.board[p.x][p.y].setColor(c);
			
//			var prevPrev = resHistory.get(resHistory.size() - 3);
//			p = Intersection.gtp2point(prevPrev.);
		}

		var pf = new PosFrame(brain, det.node, det.mistake, det.prev, fileName, det.problem, det, props);
		pf.leftPanel.add(new JLabel("ob: " + det.ownDeltaB));
		pf.leftPanel.add(new JLabel("ow: " + det.ownDeltaW));
		pf.leftPanel.add(new JLabel("sols: " + det.numSols + ", " + det.solString));
		
		return true;
	}

}
