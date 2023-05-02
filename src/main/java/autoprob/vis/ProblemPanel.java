package autoprob.vis;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.NodeChangeListener;
import autoprob.go.StoneConnect;
import autoprob.go.vis.BasicGoban;
import autoprob.go.vis.BasicGoban2D;
import autoprob.go.vis.atlas.Atlas;
import autoprob.katastruct.MoveInfo;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;

// center of UI showing the extracted problem
public class ProblemPanel extends JPanel implements NodeChangeListener {
    private static final int MARGIN = 5;
    private final Node problem;
    private final Atlas atlas;
    private BasicGoban probGoban;
    private ProbDetailPanel probDetailPanel;

    public ProblemPanel(Node problem, Atlas atlas) {
        super();
        this.problem = problem;
        this.atlas = atlas;
        setLayout(null);
        probGoban = new BasicGoban2D(problem, null) {
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
                        for (Point f : fld) {
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


            @Override
            protected void mouseEnterSquare(int x, int y) {
                if (x >= 0 && y >= 0 && x < 19 && y < 19) {
                    // on board
                    String loc = Intersection.toGTPloc(x, y, 19);
//                    System.out.println("loc: " + loc);

                    var idp = probDetailPanel.idp;
                    idp.clearEntries();
                    if (problem.kres == null) {
                        idp.addEntry("status", "no katago data");
                    } else {
                        var kres = problem.kres;
                        idp.addEntry("pos", loc);
                        int own = (int) (kres.ownership.get(x + y * 19) * 100);
                        int ownStdDev = (int) (kres.ownershipStdev.get(x + y * 19) * 100);
                        var sb = new StringBuilder();
                        sb.append(own);
                        // hint about color
                        if (own > 0) {
                            sb.append(" (B)");
                        } else {
                            sb.append(" (W)");
                        }
                        idp.addEntry("ownership", sb.toString());
                        idp.addEntry("own std dev", ownStdDev);
                        MoveInfo mi = kres.getMoveInfo(loc);
                        if (mi != null) {
                            idp.addEntry("visits", mi.visits);
                            idp.addEntry("prior", (int) (mi.prior * 1000.0)); // easier to read x1000
                            idp.addEntry("score", mi.scoreLead);
                        }

                    }
                }
            }

            @Override
            public void mouseExited(MouseEvent evt) {
//                sourceHover.setVisible(false);
            }
        };
        probGoban.setBounds(0, 0, (int) probGoban.getPreferredSize().getWidth(), (int) probGoban.getPreferredSize().getHeight());
        add(probGoban, BorderLayout.CENTER);
    }

    public BasicGoban getProbGoban() {
        return probGoban;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        probGoban.goLarge();
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

    public void resizeImages(SizeMode mode) {
        int gw, gh;
        switch (mode) {
            case LARGE -> {
                probGoban.goLarge();
                gw = (int) probGoban.getPreferredSize().getWidth();
                gh = (int) probGoban.getPreferredSize().getHeight();
                probGoban.setBounds(MARGIN, MARGIN, gw, gh);
                probDetailPanel.setBounds(MARGIN, MARGIN + gh + 1, gw, 300);
                setPreferredSize(new Dimension(gw + 2 * MARGIN, gh + 2 * MARGIN));
            }
            case SMALL -> {
                probGoban.goSmall();
                gw = (int) probGoban.getMinumumSize().getWidth();
                gh = (int) probGoban.getMinumumSize().getHeight();
                probGoban.setBounds(MARGIN, MARGIN, gw, gh);

                probDetailPanel.setBounds(MARGIN, MARGIN + gh + 1, gw, 350);
                setPreferredSize(new Dimension(gw + 2 * MARGIN, gh + 2 * MARGIN));
            }
        }
    }

    public void addProbDetailPanel(ProbDetailPanel probDetailPanel) {
        int gw = (int) probGoban.getMinumumSize().getWidth();
        int gh = (int) probGoban.getMinumumSize().getHeight();
        this.probDetailPanel = probDetailPanel;
        probDetailPanel.setBounds(MARGIN, MARGIN + gh + 1, gw, 300);
        add(probDetailPanel);
    }
}
