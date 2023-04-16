package autoprob.vis;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;

import javax.swing.*;
import java.awt.*;
import java.text.DecimalFormat;

public class SourceInfoPanel extends JPanel {
    private static final DecimalFormat df = new DecimalFormat("0.00");
    public SourceInfoPanel(KataAnalysisResult mistake, Node gameSource, KataAnalysisResult prev) {
        super();

        BoxLayout leftLayout = new BoxLayout(this, BoxLayout.PAGE_AXIS);
        setLayout(leftLayout);
        add(new JLabel("score: " + df.format(mistake.rootInfo.scoreLead)));
        add(new JLabel("delta: " + df.format((mistake.rootInfo.scoreLead - prev.rootInfo.scoreLead))));
        add(new JLabel(new String("to move: ") + (gameSource.getToMove() == Intersection.BLACK ? "black" : "white")));

        setPreferredSize(new Dimension(200, 500));
    }
}
