package autoprob.vis;

import autoprob.KataBrain;
import autoprob.PathCreator;
import autoprob.ProblemDetector;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.vis.BasicGoban;
import autoprob.katastruct.KataAnalysisResult;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

// buttons and text fields for creating paths
public class ProbDetailPanel extends JPanel {
    private final ProblemDetector det;
    private final ProblemPanel probPanel;
    private final Node problem;
    private final Properties props;
    public final IntersectionDetailPanel idp;
    //    private final BasicGoban goban;
//    private final KataAnalysisResult prev;
//    private final KataAnalysisResult mistake;
//    private final Node gameSource;
//    private final Node problem;
    private JButton makePathsButton, stopButton;
    private PathCreator pc;

    public ProbDetailPanel(Node gameSource, KataBrain brain, KataAnalysisResult prev, Node problem, Properties props, ProblemDetector det, ProblemPanel probPanel, String name) {
        super();

        this.det = det;
        this.probPanel = probPanel;
        this.problem = problem;
        this.props = props;

        setLayout(new GridLayout(2, 1));

        JPanel p1 = new JPanel();
        p1.setLayout(new FlowLayout(FlowLayout.LEFT));
        p1.add(new JLabel("bail after moves:"));
        JTextField bailNum = new JTextField(7);
        bailNum.setText(props.getProperty("paths.bailnumber"));
        bailNum.setPreferredSize(new Dimension(150, 20));
        p1.add(bailNum);

        JPanel p2 = new JPanel();
        p2.setLayout(new FlowLayout(FlowLayout.LEFT));
        p2.add(new JLabel("bail depth:"));
        JTextField bailDepth = new JTextField("3", 7);
        p2.add(bailDepth);

        JPanel p3 = new JPanel();
        p3.setLayout(new GridLayout(2, 1));

        p3.add(p1);
        p3.add(p2);

        JPanel buttonsPanel = new JPanel();
        buttonsPanel.setLayout(new FlowLayout(FlowLayout.LEFT));

        makePathsButton = new JButton("Make Paths");
//		final KataEngine keng = engine;
        makePathsButton.addActionListener(e -> {
            makePathsButton.setEnabled(false);
            stopButton.setEnabled(true);
            pc = new PathCreator(det, props);
            PathCreator.GenOptions gopts = pc.new GenOptions();
            gopts.bailNum = Integer.parseInt(bailNum.getText());
            gopts.bailDepth = Integer.parseInt(bailDepth.getText());
            createPaths(problem, det, probPanel.getProbGoban(), brain, pc, gopts);
        });
        buttonsPanel.add(makePathsButton);

        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> {
            System.out.println("aborting path creation...");
            stopButton.setEnabled(false);
            pc.abortPathCreation();
        });
        buttonsPanel.add(stopButton);

        JButton removeFillButton = new JButton("remove fill");
        removeFillButton.addActionListener(e -> removeFill());
        buttonsPanel.add(removeFillButton);

        JButton fillEmptyButton = new JButton("fill empty board");
        fillEmptyButton.addActionListener(e -> fillEmpty());
        buttonsPanel.add(fillEmptyButton);

        JButton sgfButton = new JButton("sgf");
        sgfButton.addActionListener(e -> System.out.println("(" + problem.outputSGF(true) + ")"));
        buttonsPanel.add(sgfButton);

        JButton showFileButton = new JButton("print source");
        showFileButton.addActionListener(e -> {
            System.out.println("file source:");
            System.out.println(name);
            System.out.println(prev.turnNumber);
            System.out.println("singles.add(new SingleTarget(\"" + name + "\", " + prev.turnNumber + ", true));");
        });
        buttonsPanel.add(showFileButton);
        JPanel p4 = new JPanel();
        p4.setLayout(new GridLayout(2, 1));
        p4.add(p3);
        p4.add(buttonsPanel);
        add(p4);
        idp = new IntersectionDetailPanel();
        setAlignmentX(LEFT_ALIGNMENT);
        add(idp);
    }

    // create the solution paths
    private void createPaths(Node problem, ProblemDetector det, BasicGoban probGoban, final KataBrain brain, PathCreator pc, PathCreator.GenOptions gopts) {
        System.out.println("=========== make paths ===========");
        System.out.println("opts: " + gopts);
        try {
            Thread thread = new Thread(() -> {
                System.out.println("Thread Running");
                try {
                    pc.makePaths(brain, problem, probGoban, gopts, probPanel);
                    makePathsButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    removeFill(); // clear this before outputting sgf

                    String sgf = ("(" + problem.outputSGF(true) + ")");

                    boolean writeFile = Boolean.parseBoolean(props.getProperty("output.write_file", "false"));

                    if (writeFile) {
                        String pathString = props.getProperty("output.path");
                        Path path = Paths.get(pathString);
                        byte[] strToBytes = sgf.getBytes();

                        Files.write(path, strToBytes);
                        System.out.println("wrote problem at: " + pathString);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });

            thread.start();
        } catch (Exception e1) {
            e1.printStackTrace();
        }
    }

    protected void addFill(int x, int y, int stone) {
        Intersection[][] b = problem.board.board;
        b[x][y].stone = stone;
        // remember so we can remove later
        det.filledStones.board[x][y].stone = stone;
    }

    protected void fillCorner(int x, int y, int dx, int dy) {
        Intersection[][] b = problem.board.board;
        int srch = 7;
        // make sure empty
        int scount;
        scount = 0;
        for (int px = x; px != x + dx * srch; px += dx)
            for (int py = y; py != y + dy * srch; py += dy) {
                if (b[px][py].stone != 0)
                    scount++;
            }
        int stone = Intersection.BLACK;
        if (Math.random() > 0.5)
            stone = Intersection.WHITE;
        if (scount == 0) {
            // place stones
            addFill(x + dx * 2, y + dy * 1, stone);
            addFill(x + dx * 1, y + dy * 2, stone);
            addFill(x + dx * 2, y + dy * 3, stone);
            addFill(x + dx * 3, y + dy * 2, stone);
            addFill(x + dx * 3, y + dy * 2, stone);
            addFill(x + dx * 4, y + dy * 1, stone);
            addFill(x + dx * 1, y + dy * 4, stone);
        }
    }

    // put down some solid stones so kata isn't searching empty here
    protected void fillEmpty() {
        fillCorner(0, 0, 1, 1);
        fillCorner(18, 0, -1, 1);
        fillCorner(0, 18, 1, -1);
        fillCorner(18, 18, -1, -1);
        getParent().repaint();
    }

    protected void removeFill() {
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (det.filledStones.board[x][y].stone != 0) {
                    det.filledStones.board[x][y].stone = 0;
                    problem.board.board[x][y].stone = 0;
                }
            }
        getParent().repaint();
    }
}
