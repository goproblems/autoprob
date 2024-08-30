package autoprob;

import autoprob.go.Intersection;
import autoprob.katastruct.MoveInfo;
import autoprob.vis.PosFrame;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.Properties;

// creates a visible frame to show a detected problem
public class VisDetector {
    private static final DecimalFormat df = new DecimalFormat("0.00");
    private static ProblemDetector prevDetection;
    private Properties props;

    public VisDetector(Properties props) {
        this.props = props;
    }

    // process a detection, including rejecting it if it's a duplicate
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
        if (prevDetection != null && prevDetection.solString != null) {
            if (prevDetection.solString.equals(det.solString)) {
                System.out.println("* skipping dup sol string: " + det.solString);
                return false;
            }
        }

        prevDetection = det;

        boolean saveToOutput = Boolean.parseBoolean(props.getProperty("output.save2dir", "false"));

        if (saveToOutput) {
            // save to output dir
            String outDir = props.getProperty("output.dir", "output");
            String outPath = outDir + "/" + fileName + det.getFileNameExtras() + ".sgf";
            System.out.println("saving to: " + outPath);
            saveToFile(det, outPath);
            if (Boolean.parseBoolean(props.getProperty("output.no_gui", "false"))) {
                return true;
            }
        }

        double baseScore = det.prev.rootInfo.scoreLead; // represents best
        // decorate source board
        // put other best moves on the board
        for (MoveInfo mi : det.prev.moveInfos) {
            String spos = mi.move;
            Point p = Intersection.gtp2point(spos);

            double scoreDiff = Math.abs(baseScore - mi.scoreLead); // could be either direction depending on whose move it is
            double mx = 15;
            System.out.println("move: " + mi.move + ", diff: " + df.format(scoreDiff) + ", visits: " + mi.visits);
            scoreDiff = Math.min(scoreDiff, mx); // cap visual
            int green = 100 + (int) (150 * (1.0 - scoreDiff / mx));
            green = Math.min(green, 255);
            Color c = new Color(80, green, 80);

            if (p.x == 19 || p.y == 19)
                continue; // pass
            det.node.board.board[p.x][p.y].setColor(c);

//			var prevPrev = resHistory.get(resHistory.size() - 3);
//			p = Intersection.gtp2point(prevPrev.);
        }

        // add some details
        var pf = new PosFrame(brain, det.node, det.mistake, det.prev, fileName, det.problem, det, props);
        pf.addSourceInfoPanelEntry("b chng:", String.valueOf(det.ownDeltaB));
        pf.addSourceInfoPanelEntry("w chng: ", String.valueOf(det.ownDeltaW));
        pf.addSourceInfoPanelEntry("#sols: ", det.numSols + ", " + det.solString);
        return true;
    }

    private void saveToFile(ProblemDetector det, String outPath) {
        String sgf = ("(" + det.problem.outputSGF(true) + ")");
        Path path = Paths.get(outPath);
        byte[] strToBytes = sgf.getBytes();

        try {
            Files.write(path, strToBytes);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        System.out.println("wrote problem at: " + outPath);
    }
}
