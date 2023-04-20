package autoprob.vis;

import autoprob.KataBrain;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.StoneConnect;
import autoprob.go.vis.BasicGoban;
import autoprob.go.vis.BasicGoban2D;
import autoprob.katastruct.KataAnalysisResult;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.Properties;

public class SourcePanel extends JPanel {
    private final BasicGoban goban;
    private final JLabel sourceHover;

    public SourcePanel(Node gameSource, KataBrain brain, KataAnalysisResult prev, Node problem, Properties props) {
        super();

//        BoxLayout sourceLayout = new BoxLayout(this, BoxLayout.PAGE_AXIS);
//        setLayout(sourceLayout);
//        setLayout(new BorderLayout());
//        setLayout(new FlowLayout());
        setLayout(null);
        sourceHover = new JLabel("...");
        sourceHover.setVisible(false);

        goban = new BasicGoban2D(gameSource, prev.ownership) {
            @Override
            public void clickSquare(Point p, MouseEvent e) {
//				System.out.println("src: " + p);
                StoneConnect sc = new StoneConnect();
                if (sc.isOnboard(p.x, p.y) && node.board.board[p.x][p.y].stone != 0) {
                    // copy flood from source
                    var fld = sc.floodFromStone(p, node.board);
                    for (Point f : fld) {
                        problem.board.board[f.x][f.y].stone = node.board.board[p.x][p.y].stone;
                    }
                    SourcePanel.this.repaint();
                    SourcePanel.this.getParent().repaint();
                }
            }

            @Override
            protected void mouseEnterSquare(int x, int y) {
                if (x >= 0 && y >= 0 && x < 19 && y < 19) {
                    // on board
                    StringBuilder sb = new StringBuilder();
                    sb.append("pos: ").append(Intersection.toGTPloc(x, y, 19));
                    sb.append(", ownership: ").append((int) (prev.ownership.get(x + y * 19) * 100));
                    if (problem.board.board[x][y].stone == 0)
                        sb.append(", policy: ").append((int) (prev.policy.get(x + y * 19) * 1000));
//					System.out.println();
                    sourceHover.setVisible(true);
                    sourceHover.setText(sb.toString());
                }
            }

            @Override
            public void mouseExited(MouseEvent evt) {
                sourceHover.setVisible(false);
            }
        };
//        goban.setBounds(0, 0, 600, 600);
//        srcgoban.goLarge();
//        add(goban, BorderLayout.CENTER);
        goban.setBounds(0, 0, 600, 600);
        add(goban);
        sourceHover.setBackground(Color.GREEN);
        sourceHover.setOpaque(true);
        sourceHover.setBounds(0, 610, 600, 50);
        add(sourceHover);
//        maxSize = goban.getSize();
//        add(sourceHover, BorderLayout.SOUTH);
    }

    @Override
    public void addNotify() {
        super.addNotify();
        goban.goLarge();
    }

    public void resizeImages(SizeMode mode) {
        switch (mode) {
            case LARGE -> {
                goban.goLarge();
                goban.setBounds(0, 0, 600, 600);
            }
            case SMALL -> {
                goban.goSmall();
                goban.setBounds(0, 0, (int) goban.getMinumumSize().getWidth(), (int) goban.getMinumumSize().getHeight());
                sourceHover.setBounds(0, (int) goban.getMinumumSize().getHeight() + 1, 600, 20);
            }
        }
    }
}
