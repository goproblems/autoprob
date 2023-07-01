package autoprob.vis;

import autoprob.KataRunner;
import autoprob.go.Node;
import autoprob.go.vis.BasicGoban;
import autoprob.util.KataObserver;

import javax.swing.*;
import java.awt.*;

//@Todo work in progress. start/stop button not working yet
public class TestAnalyzeFrame extends JFrame implements KataObserver {
    private final BasicGoban leftBoard, rightBoard;

    private final JButton startStopButton;

    private final JLabel foundCountLabel, stoneDeltaLabel, solutionsLabel, gamesSearchedLabel;

    // Call this for initializing the UI and registering it as an observer in KataRunner
    public TestAnalyzeFrame(KataRunner kataRunner) {
        if (kataRunner != null) {
            kataRunner.addKataObserver(this);
        }
        setLayout(new BorderLayout());

        JPanel boardsPanel = new JPanel();
        boardsPanel.setLayout(new GridLayout(1, 2));
        leftBoard = new BasicGoban(null);
        rightBoard = new BasicGoban(null);
        boardsPanel.add(leftBoard);
        boardsPanel.add(rightBoard);

        add(boardsPanel, BorderLayout.CENTER);
        JPanel leftStatsPanel = new JPanel();
        JPanel rightStatsPanel = new JPanel();

        startStopButton = new JButton("Start");
        leftStatsPanel.add(startStopButton);
        startStopButton.setPreferredSize(new Dimension(200, 30));
        gamesSearchedLabel = initLabel("Games Searched: ", leftStatsPanel);
        foundCountLabel = initLabel("Problems Found: ", leftStatsPanel);
        stoneDeltaLabel = initLabel("Stone Delta: ", rightStatsPanel);
        solutionsLabel = initLabel("Solutions: ", rightStatsPanel);

        JPanel statsPanel = new JPanel();
        statsPanel.setPreferredSize(new Dimension(500, 100));
        statsPanel.setMaximumSize(new Dimension(500, 100));
        statsPanel.setLayout(new GridLayout(1, 2));
        statsPanel.add(leftStatsPanel);
        statsPanel.add(rightStatsPanel);
        add(statsPanel, BorderLayout.SOUTH);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 600);
        setVisible(true);
    }

    public static void main(String[] args) {
        new TestAnalyzeFrame(null);
    }

    private JLabel initLabel(String text, JPanel panel) {
        JLabel label = new JLabel(text);
        label.setPreferredSize(new Dimension(200, 20));
//        label.setBackground(Color.YELLOW);
        label.setOpaque(true);
        panel.add(label);
        return label;
    }

    @Override
    public void updateLeftBoard(Node node) {
        leftBoard.newCurrentNode(node);
    }

    @Override
    public void updateRightBoard(Node node) {
        rightBoard.newCurrentNode(node);
    }

    @Override
    public void updateFoundCount(int foundCount) {
        foundCountLabel.setText("Problems Found: " + foundCount);
    }

    @Override
    public void updateStoneDelta(double stoneDelta) {
        stoneDeltaLabel.setText("Stone Delta: " + stoneDelta);
    }

    @Override
    public void updateSolutions(double solutions) {
        solutionsLabel.setText("Solutions: " + solutions);
    }

    @Override
    public void updateGamesSearched(int gamesSearched) {
        gamesSearchedLabel.setText("Games Searched: " + gamesSearched);
    }
}
