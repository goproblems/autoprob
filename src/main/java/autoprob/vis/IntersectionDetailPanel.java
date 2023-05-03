package autoprob.vis;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;

import javax.swing.*;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.text.DecimalFormat;

public class IntersectionDetailPanel extends JPanel {
    private static final DecimalFormat df = new DecimalFormat("0.00");

    private JPanel gridPanel;

    public IntersectionDetailPanel() {
        super();

//        BoxLayout leftLayout = new BoxLayout(this, BoxLayout.PAGE_AXIS);
//        setLayout(leftLayout);
        setLayout(new FlowLayout());
        gridPanel = new JPanel();
        gridPanel.setLayout(new GridLayout(0, 2));
        gridPanel.setPreferredSize(new Dimension(230, 150));

        addEntry("status", "no data");
        addEntry("foo", "bar");

        gridPanel.setBorder(new LineBorder(Color.BLUE));
        add(gridPanel);
        setPreferredSize(new Dimension(230, 150));
    }

    public void addEntry(String key, String value) {
        gridPanel.add(new JLabel(key));
        gridPanel.add(new JLabel(value));
        revalidate();
        repaint();
    }

    public void addEntry(String key, int value) {
        addEntry(key, Integer.toString(value));
    }

    public void addEntry(String key, double value) {
        addEntry(key, df.format(value));
    }

        // clear all entries
    public void clearEntries() {
        gridPanel.removeAll();
    }
}
