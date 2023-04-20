package autoprob.vis;


import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;

import javax.swing.*;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.text.DecimalFormat;

public class SourceInfoPanel extends JPanel {
    private static final DecimalFormat df = new DecimalFormat("0.00");

    public SourceInfoPanel(KataAnalysisResult mistake, Node gameSource, KataAnalysisResult prev) {
        super();

//        BoxLayout leftLayout = new BoxLayout(this, BoxLayout.PAGE_AXIS);
//        setLayout(leftLayout);
        setLayout(new FlowLayout());
        JPanel gridPanel = new JPanel();
        gridPanel.setLayout(new GridLayout(3, 2));
        gridPanel.setPreferredSize(new Dimension(130, 150));
        gridPanel.add(new JLabel("score: "));
        gridPanel.add(new JLabel(df.format(mistake.rootInfo.scoreLead)));
        gridPanel.add(new JLabel("delta: "));
        gridPanel.add(new JLabel(df.format((mistake.rootInfo.scoreLead - prev.rootInfo.scoreLead))));
        gridPanel.add(new JLabel("to move: "));
        gridPanel.add(new JLabel((gameSource.getToMove() == Intersection.BLACK ? "black" : "white")));
        gridPanel.setBorder(new LineBorder(Color.RED));
        add(gridPanel);
        setPreferredSize(new Dimension(130, 500));
    }
}
