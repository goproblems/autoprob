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
	private static final int MIN_WIDTH_FOR_LARGE_IMAGES = 2251;
	static int totalProblemCount = 0;
	public JPanel leftPanel;
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

		setSize(2000, 1050);
		currentSizeMode = SizeMode.LARGE;
		// position window not to overlap too much other popup windows
		int loop = totalProblemCount / 30;
		setLocation(20 * (totalProblemCount % 30), 10 * totalProblemCount + loop * 12);
		totalProblemCount++;

//        GridBagLayout layout = new GridBagLayout();
//        getContentPane().setLayout(layout);
		setLayout(new BorderLayout());
//        GridBagConstraints c = new GridBagConstraints();
//        c.fill = GridBagConstraints.BOTH;
//        c.weightx = 1;
//        c.weighty = 1;

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
		leftPanel = new SourceInfoPanel(mistake, gameSource, prev);

//		add(leftPanel);

		// source game goban
		SourcePanel sourcePanel = new SourcePanel(gameSource, brain, prev, problem, props);
//		add(sourcePanel);
//		add(srcgoban, BorderLayout.CENTER);

		atlas = new Atlas2D(problem);

		// problem
		ProblemPanel probPanel = new ProblemPanel(problem, atlas);

		JPanel probDetailPanel = new ProbDetailPanel(gameSource, brain, prev, problem, props, det, probPanel, name);

//        probPanel.add(probDetailPanel);

//		add(probPanel);
//		add(probPanel, BorderLayout.EAST);

		JScrollPane atlasPane = new JScrollPane(atlas);
//        atlasPane.getViewport().setBackground(Pix.colBack);
		atlasPane.getVerticalScrollBar().setUnitIncrement(10);
		atlasPane.getHorizontalScrollBar().setUnitIncrement(10);
//        add(atlasPane);

		// Adding components to the main layout using GridBagLayout
//        c.gridx = 0;
//        c.gridy = 0;
////		c.fill = GridBagConstraints.NONE;
//        add(leftPanel, c);
//
//        c.gridx = 1;
//        c.gridy = 0;
//        add(sourcePanel, c);
//
//        c.gridx = 2;
//        c.gridy = 0;
//        add(probPanel, c);
//
//        c.gridx = 3;
//        c.gridy = 0;
//        c.fill = GridBagConstraints.BOTH;
//        add(atlasPane, c);
//        sourcePanel.setBackground(Color.BLUE);
//        probPanel.setBackground(Color.RED);
		JPanel boardsPanel = new JPanel();
//        boardsPanel.setBackground(Color.YELLOW);
		boardsPanel.setLayout(new GridLayout(1, 2));
		boardsPanel.add(sourcePanel);
		boardsPanel.add(probPanel);

		add(leftPanel, BorderLayout.WEST);

		JPanel centerPanel = new JPanel();
		centerPanel.setLayout(new BorderLayout());
		centerPanel.add(boardsPanel, BorderLayout.WEST);
		centerPanel.add(atlasPane, BorderLayout.CENTER);
		add(centerPanel, BorderLayout.CENTER);
		addComponentListener(new ComponentAdapter() {
			public void componentResized(ComponentEvent componentEvent) {
				SizeMode sizeMode = getWidth() < MIN_WIDTH_FOR_LARGE_IMAGES ? SizeMode.SMALL : SizeMode.LARGE;
//                if (currentSizeMode == sizeMode) return;
				if(currentSizeMode != sizeMode) {
					currentSizeMode = sizeMode;
					sourcePanel.resizeImages(sizeMode);
					probPanel.resizeImages(sizeMode);
				}
				if (sizeMode == SizeMode.SMALL)
					boardsPanel.setPreferredSize(new Dimension(340 * 2 + 10, 340 * 2));
				else
					boardsPanel.setPreferredSize(new Dimension(676 * 2 + 10, 676 * 2));
			}
		});
		pack();
		setVisible(true);// making the frame visible
	}


}
