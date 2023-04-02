package autoprob.vis;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Point;
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

public class PosFrame extends JFrame implements NodeChangeListener {
    private static final DecimalFormat df = new DecimalFormat("0.00");
	static int totalProblemCount = 0;
	public JPanel leftPanel;
	private ProblemDetector det;
	private Atlas atlas;
	private Node problem;
	private JButton makePathsButton;

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
		
		// position not to overlap too much
		int loop = totalProblemCount / 30;
		setLocation(20 * (totalProblemCount % 30), 10 * totalProblemCount + loop * 12);
		totalProblemCount++;

		BoxLayout layout = new BoxLayout(getContentPane(), BoxLayout.LINE_AXIS);
		getContentPane().setLayout(layout);
		
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
		leftPanel = new JPanel();
		BoxLayout leftLayout = new BoxLayout(leftPanel, BoxLayout.PAGE_AXIS);
		leftPanel.setLayout(leftLayout);
		leftPanel.add(new JLabel("score: " + df.format(mistake.rootInfo.scoreLead)));
		leftPanel.add(new JLabel("delta: " + df.format((mistake.rootInfo.scoreLead - prev.rootInfo.scoreLead))));
		leftPanel.add(new JLabel(new String("to move: ") + (gameSource.getToMove() == Intersection.BLACK ? "black" : "white")));
		
		add(leftPanel);

		// source game goban
		JPanel sourcePanel = new JPanel();
		BoxLayout sourceLayout = new BoxLayout(sourcePanel, BoxLayout.PAGE_AXIS);
		sourcePanel.setLayout(sourceLayout);
		
		JLabel sourceHover = new JLabel("...");

		BasicGoban srcgoban = new BasicGoban2D(gameSource, prev.ownership) {
			@Override
			public void clickSquare(Point p, MouseEvent e) {
//				System.out.println("src: " + p);
				StoneConnect sc = new StoneConnect();
				if (sc.isOnboard(p.x, p.y) && node.board.board[p.x][p.y].stone != 0) {
					// copy flood from source
					var fld = sc.floodFromStone(p, node.board);
					for (Point f: fld) {
						problem.board.board[f.x][f.y].stone = node.board.board[p.x][p.y].stone;
					}
					PosFrame.this.repaint();
				}
			}

			@Override
			protected void mouseEnterSquare(int x, int y) {
				if (x >= 0 && y >= 0 && x < 19 && y < 19) {
					// on board
					StringBuilder sb = new StringBuilder();
					sb.append("pos: " + Intersection.toGTPloc(x, y, 19));
					sb.append(", ownership: " + (int)(prev.ownership.get(x + y * 19) * 100));
					if (problem.board.board[x][y].stone == 0)
						sb.append(", policy: " + (int)(prev.policy.get(x + y * 19) * 1000));
//					System.out.println();
					sourceHover.setText(sb.toString());
				}
			}
		};
		srcgoban.setBounds(0, 0, 600, 600);
		sourcePanel.add(srcgoban);
		sourcePanel.add(sourceHover);
		add(sourcePanel);
//		add(srcgoban, BorderLayout.CENTER);

		// problem
		JPanel probPanel = new JPanel();
		BoxLayout probLayout = new BoxLayout(probPanel, BoxLayout.PAGE_AXIS);
		probPanel.setLayout(probLayout);
		BasicGoban probGoban = new BasicGoban2D(problem, null) {
			@Override
			public void clickSquare(Point p, MouseEvent e) {
//				System.out.println("prob: " + p);
				// edit board
				repaint();
				if (e.isControlDown()) {
					// flood delete
					StoneConnect sc = new StoneConnect();
					if (sc.isOnboard(p.x, p.y)) {
						// copy flood from source
						var fld = sc.floodFromStone(p, problem.board);
						for (Point f: fld) {
							problem.board.board[f.x][f.y].stone = Intersection.EMPTY;
						}
					}
					return;
				}
				boolean rtmouse = SwingUtilities.isRightMouseButton(e);
				int stn = rtmouse ? Intersection.WHITE : Intersection.BLACK;
				if (problem.board.board[p.x][p.y].stone == stn)
					problem.board.board[p.x][p.y].stone = Intersection.EMPTY;
				else
					problem.board.board[p.x][p.y].stone = stn;
			}
		};
		probGoban.setBounds(0, 0, 400, 400);
		probPanel.add(probGoban);
		
		// bail num
		probPanel.add(new JLabel("bail after moves:"));
		JTextField bailNum = new JTextField(7);
		bailNum.setText(props.getProperty("paths.bailnumber"));
		bailNum.setPreferredSize(new Dimension(150, 20));
		probPanel.add(bailNum);
		
		// bail depth
		probPanel.add(new JLabel("bail depth:"));
		JTextField bailDepth = new JTextField("3", 7);
		probPanel.add(bailDepth);
		
		makePathsButton = new JButton("Make Paths");
//		final KataEngine keng = engine;
		makePathsButton.addActionListener(new ActionListener() {
	         public void actionPerformed(ActionEvent e) {
	        	 makePathsButton.setEnabled(false);
	        	 PathCreator pc = new PathCreator(det, props);
	        	 GenOptions gopts = pc.new GenOptions();
	        	 int bail = Integer.parseInt(bailNum.getText());
	        	 gopts.bailNum = bail;
	        	 gopts.bailDepth = Integer.parseInt(bailDepth.getText());
	        	 createPaths(problem, det, probGoban, brain, pc, gopts);
	         }
		});
		probPanel.add(makePathsButton);
		
		JButton removeFillButton = new JButton("remove fill");
		removeFillButton.addActionListener(new ActionListener() {
	         public void actionPerformed(ActionEvent e) {
	        	 removeFill();
	         }
		});
		probPanel.add(removeFillButton);
		JButton fillEmptyButton = new JButton("fill empty board");
		fillEmptyButton.addActionListener(new ActionListener() {
	         public void actionPerformed(ActionEvent e) {
	        	 fillEmpty();
	         }
		});
		probPanel.add(fillEmptyButton);
		
		JButton sgfButton = new JButton("sgf");
		sgfButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				System.out.println("(" + problem.outputSGF(true) + ")");
			}
		});
		probPanel.add(sgfButton);
		
		JButton showFileButton = new JButton("print source");
		showFileButton.addActionListener(new ActionListener() {
	         public void actionPerformed(ActionEvent e) {
	        	 System.out.println("file source:");
	        	 System.out.println(name);
	        	 System.out.println(prev.turnNumber);
	        	 System.out.println("singles.add(new SingleTarget(\"" + name + "\", " + prev.turnNumber + ", true));");
	         }
		});
		probPanel.add(showFileButton);
		
		add(probPanel);
//		add(probPanel, BorderLayout.EAST);
		
        atlas = new Atlas2D(problem);
        JScrollPane atlasPane = new JScrollPane(atlas);
//        atlasPane.getViewport().setBackground(Pix.colBack);
        atlasPane.getVerticalScrollBar().setUnitIncrement(10);
        atlasPane.getHorizontalScrollBar().setUnitIncrement(10);
        add(atlasPane);

		setVisible(true);// making the frame visible

		srcgoban.goLarge();
		probGoban.goLarge();
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
		repaint();
	}
	
	protected void removeFill() {
		for (int x = 0; x < 19; x++)
			for (int y = 0; y < 19; y++) {
				if (det.filledStones.board[x][y].stone != 0) {
					det.filledStones.board[x][y].stone = 0;
					problem.board.board[x][y].stone = 0;
				}
			}
		repaint();
	}

	// create the solution paths
	private void createPaths(Node problem, ProblemDetector det, BasicGoban probGoban, final KataBrain brain, PathCreator pc, GenOptions gopts) {
		System.out.println("=========== make paths ===========");
		System.out.println("opts: " + gopts);
		try {
			Thread thread = new Thread() {
				public void run() {
					System.out.println("Thread Running");
					try {
						pc.makePaths(brain, problem, probGoban, gopts, PosFrame.this);
						makePathsButton.setEnabled(true);
						removeFill(); // clear this before outputting sgf

						String sgf = ("(" + problem.outputSGF(true) + ")");

						String home = System.getProperty("user.home");
						System.out.println("wrote problem at home: " + home);
						Path path = Paths.get(home + "\\" + "autoprob.sgf");
						byte[] strToBytes = sgf.getBytes();

						Files.write(path, strToBytes);
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			};

			thread.start();
		} catch (Exception e1) {
			e1.printStackTrace();
		}
	}

	@Override
	public void newCurrentNode(Node node) {
	}

	@Override
	public void nodeChanged(Node node) {
		atlas.calculatePositions(node.getRoot());
		problem.markCrayons();

		// track what we're searching visually
        Point lastMove = node.findMove();
        if (lastMove != null) {
        	if (lastMove.x < 19)
        		problem.board.board[lastMove.x][lastMove.y].setMarkup(Intersection.MARK_SQUARE);
        }

        revalidate();
        repaint();
	}

}
