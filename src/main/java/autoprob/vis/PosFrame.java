package autoprob.vis;

import autoprob.KataBrain;
import autoprob.ProblemDetector;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.vis.Pix;
import autoprob.go.vis.atlas.Atlas;
import autoprob.go.vis.atlas.Atlas2D;
import autoprob.katastruct.KataAnalysisResult;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.text.DecimalFormat;
import java.util.Properties;

public class PosFrame extends JFrame {
    private static final DecimalFormat df = new DecimalFormat("0.00");
    private static final int MIN_WIDTH_FOR_LARGE_IMAGES = 2000;
    private static final int BOARDS_GAP = 10;
    static int totalProblemCount = 0;
    private final SourceInfoPanel sourceInfoPanel;
    private final SourcePanel sourcePanel;
    private final ProblemPanel probPanel;
    private final JPanel boardsPanel;
    private ProblemDetector det;
    private Atlas atlas;
    private Node problem;
    private SizeMode currentSizeMode;
    ;

    /**
     * @param brain
     * @param gameSource source of game to show
     * @param mistake    KAR after mistake made
     * @param prev       KAR before mistake
     * @param name
     * @param problem
     * @param det
     */
    public PosFrame(KataBrain brain, Node gameSource, KataAnalysisResult mistake, KataAnalysisResult prev, String name, Node problem, ProblemDetector det
            , Properties props) {
        super("detected problem: " + prev.turnNumber + " -- " + name);

        this.det = det;
        this.problem = problem;

        setLayout(new BorderLayout());
        setSize(2000, 1050);
        // position window not to overlap too much other popup windows
        int loop = totalProblemCount / 30;
        setLocation(20 * (totalProblemCount % 30), 10 * totalProblemCount + loop * 12);
        totalProblemCount++;

        Pix.LoadAll();

        // decorate game source
        // add previous move
        Point lastMove = gameSource.mom.findMove();
        if (lastMove != null) {
            gameSource.board.board[lastMove.x][lastMove.y].setMarkup(Intersection.MARK_TRIANGLE);
        }
        // add next move
        Node child = gameSource.favoriteSon();
        Point nextMove = child.findMove();
        gameSource.board.board[nextMove.x][nextMove.y].setColor(Color.RED);

        // indicate ownership change
//        for (Point op: det.fullOwnershipChanges) {
        if (det.fullOwnNeighbors != null) {
            for (Point op : det.fullOwnNeighbors) {
                if (problem.board.board[op.x][op.y].stone != 0) continue;
                problem.board.board[op.x][op.y].setColor(Color.BLUE);
            }
        }
        for (Point op : det.ownershipChanges) {
//			if (problem.board.board[op.x][op.y].stone != 0) continue;
            problem.board.board[op.x][op.y].setColor(Color.CYAN);
        }

        // left info
        sourceInfoPanel = new SourceInfoPanel(mistake, gameSource, prev);

        // source game goban
        sourcePanel = new SourcePanel(gameSource, brain, prev, problem, props);

        atlas = new Atlas2D(problem);

        // problem
        probPanel = new ProblemPanel(problem, atlas);

        probPanel.addProbDetailPanel(new ProbDetailPanel(gameSource, brain, prev, problem, props, det, probPanel, name));

        JScrollPane atlasPane = new JScrollPane(atlas);
        atlasPane.getVerticalScrollBar().setUnitIncrement(10);
        atlasPane.getHorizontalScrollBar().setUnitIncrement(10);

        boardsPanel = new JPanel();
        boardsPanel.setLayout(new GridLayout(1, 2, BOARDS_GAP, 0));
        boardsPanel.add(sourcePanel);
        boardsPanel.add(probPanel);

        add(sourceInfoPanel, BorderLayout.WEST);

        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BorderLayout());
        centerPanel.add(boardsPanel, BorderLayout.WEST);
        centerPanel.add(atlasPane, BorderLayout.CENTER);
        add(centerPanel, BorderLayout.CENTER);
        addComponentListener(new ComponentAdapter() {
            public void componentShown(ComponentEvent evt) {
                calcLayout();
            }

            public void componentResized(ComponentEvent componentEvent) {
                calcLayout();
            }
        });
        setVisible(true);// making the frame visible
    }

    private void calcLayout() {
        SizeMode sizeMode = getWidth() < MIN_WIDTH_FOR_LARGE_IMAGES ? SizeMode.SMALL : SizeMode.LARGE;
        if (currentSizeMode != sizeMode) {
            currentSizeMode = sizeMode;
            sourcePanel.resizeImages(sizeMode);
            probPanel.resizeImages(sizeMode);
        }
        int sw = (int) sourcePanel.getPreferredSize().getWidth();
        int sh = (int) sourcePanel.getPreferredSize().getHeight();
        int pw = (int) probPanel.getPreferredSize().getWidth();
        int ph = (int) probPanel.getPreferredSize().getHeight();
        boardsPanel.setPreferredSize(new Dimension(sw + pw, Math.max(sh, ph)));
        revalidate();
    }

    public void addSourceInfoPanelEntry(String key, String value) {
        sourceInfoPanel.addEntry(key, value);
    }
}
