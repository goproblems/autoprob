package autoprob.vis;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.Properties;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import autoprob.KataBrain;
import autoprob.PathCreator;
import autoprob.PathCreator.GenOptions;
import autoprob.ProblemDetector;
import autoprob.go.StoneConnect;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.NodeChangeListener;
import autoprob.go.vis.BasicGoban;
import autoprob.go.vis.BasicGoban2D;
import autoprob.go.vis.Pix;
import autoprob.go.vis.atlas.Atlas;
import autoprob.go.vis.atlas.Atlas2D;
import autoprob.katastruct.KataAnalysisResult;

public class PosFrame extends JFrame {
    private static final DecimalFormat df = new DecimalFormat("0.00");
	static int totalProblemCount = 0;
	public JPanel leftPanel;
	private ProblemDetector det;
	private Atlas atlas;
	private Node problem;

	/**
	 * 
	 * @param brain 
	 * @param gameSource source of game to show
	 * @param mistake KAR after mistake made
	 * @param prev KAR before mistake
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
		
		// position window not to overlap too much other popup windows
		int loop = totalProblemCount / 30;
		setLocation(20 * (totalProblemCount % 30), 10 * totalProblemCount + loop * 12);
		totalProblemCount++;

		GridBagLayout layout = new GridBagLayout();
		getContentPane().setLayout(layout);

		GridBagConstraints c = new GridBagConstraints();
		c.fill = GridBagConstraints.BOTH;
		c.weightx = 1;
		c.weighty = 1;

//		BoxLayout layout = new BoxLayout(getContentPane(), BoxLayout.LINE_AXIS);
//		getContentPane().setLayout(layout);
		
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
			for (Point op: det.fullOwnNeighbors) {
				if (problem.board.board[op.x][op.y].stone != 0) continue;
				problem.board.board[op.x][op.y].setColor(Color.BLUE);
			}
        }
		for (Point op: det.ownershipChanges) {
//			if (problem.board.board[op.x][op.y].stone != 0) continue;
			problem.board.board[op.x][op.y].setColor(Color.CYAN);
		}
        
		// left info
		leftPanel = new SourceInfoPanel(mistake, gameSource, prev);

//		add(leftPanel);

		// source game goban
		JPanel sourcePanel = new SourcePanel(gameSource, brain, prev, problem, props);

//		add(sourcePanel);
//		add(srcgoban, BorderLayout.CENTER);

		// problem
		ProblemPanel probPanel = new ProblemPanel(problem);

		JPanel probDetailPanel = new ProbDetailPanel(gameSource, brain, prev, problem, props, det, probPanel, name);

		probPanel.add(probDetailPanel);
		
//		add(probPanel);
//		add(probPanel, BorderLayout.EAST);
		
        atlas = new Atlas2D(problem);
        JScrollPane atlasPane = new JScrollPane(atlas);
//        atlasPane.getViewport().setBackground(Pix.colBack);
        atlasPane.getVerticalScrollBar().setUnitIncrement(10);
        atlasPane.getHorizontalScrollBar().setUnitIncrement(10);
//        add(atlasPane);

		// Adding components to the main layout using GridBagLayout
		c.gridx = 0;
		c.gridy = 0;
//		c.fill = GridBagConstraints.NONE;
		add(leftPanel, c);

		c.gridx = 1;
		c.gridy = 0;
		add(sourcePanel, c);

		c.gridx = 2;
		c.gridy = 0;
		add(probPanel, c);

		c.gridx = 3;
		c.gridy = 0;
		c.fill = GridBagConstraints.BOTH;
		add(atlasPane, c);

		setVisible(true);// making the frame visible
	}
}
