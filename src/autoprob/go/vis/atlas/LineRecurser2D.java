package autoprob.go.vis.atlas;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;

import autoprob.go.Node;

//draw lines between nodes
class LineRecurser2D extends LineRecurser {
    private static final BasicStroke activeStroke   = new BasicStroke(4.0f, BasicStroke.CAP_ROUND,
                                                            BasicStroke.JOIN_ROUND);
    private static final BasicStroke inactiveStroke = new BasicStroke(1.0f, BasicStroke.CAP_ROUND,
                                                            BasicStroke.JOIN_ROUND);
    private static final BasicStroke newPathStrokeInactive = new BasicStroke(
            1f, 
            BasicStroke.CAP_ROUND, 
            BasicStroke.JOIN_ROUND, 
            1f, 
            new float[] {2f, 4f}, 
            0f);
    private static final BasicStroke newPathStrokeActive = new BasicStroke(
            4f, 
            BasicStroke.CAP_ROUND, 
            BasicStroke.JOIN_ROUND, 
            1f, 
            new float[] {2f, 8f}, 
            0f);
    private static final BasicStroke commentStrokeInactive = new BasicStroke(
            1f, 
            BasicStroke.CAP_SQUARE, 
            BasicStroke.JOIN_ROUND, 
            1f, 
            new float[] {4f, 2f}, 
            0f);
    private static final BasicStroke commentStrokeActive = new BasicStroke(
            2.5f, 
            BasicStroke.CAP_SQUARE, 
            BasicStroke.JOIN_ROUND, 
            1f, 
            new float[] {4f, 8f}, 
            0f);
    private static final Color PATH_COLOR = new Color(0x00555555);
    private static final Color COMMENT_COMMENT_COLOR = new Color(0x00545d6d);
    private static final Color COMMENT_QUESTION_COLOR = new Color(0x0028a1af);
    private static final Color COMMENT_RIGHT_COLOR = new Color(0x007fc876).darker();
    private static final Color COMMENT_WRONG_COLOR = new Color(0x00c8856e);
    
    public LineRecurser2D(Atlas atlas, Graphics g) {
        super(atlas, g);
    }

    public void action(Node n) {
        // draw a line to mommy
        if (n.mom == null) return;

        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Point d = atlas.nodeDrawPos(n);
        Point dm = atlas.nodeDrawPos(n.mom);
        Point target = new Point();
        if (n.searchForTheTruth())
            g.setColor(Color.green);
        else g.setColor(Color.red);
        if (n.nodeType == Node.TYPE_PATH) g.setColor(PATH_COLOR);

        boolean chosenPath = false;//n.onChosenPath(atlas.globals.getCurNode());
        Stroke oldStroke = g2.getStroke();
        if (chosenPath) {
            if (n.originalPath)
                g2.setStroke(activeStroke);
            else if (n.nodeType == Node.TYPE_COMMENT)
                g2.setStroke(commentStrokeActive);
            else
                g2.setStroke(newPathStrokeActive);
        }
        else {
            if (n.originalPath)
                g2.setStroke(inactiveStroke);
            else if (n.nodeType == Node.TYPE_COMMENT)
                g2.setStroke(commentStrokeInactive);
            else
                g2.setStroke(newPathStrokeInactive);
        }

        // if there's a big vertical drop, we don't want to make a
        // slanted line all the way, so we drop vertically until just
        // before this node
        if (n.atlasY >= n.mom.atlasY + 2) {
            Point mid = atlas.atlasDrawPos(n.depth - 1, n.atlasY - 1);
            doLineThang(n, g2, mid, dm); // drop
            doLineThang(n, g2, mid, d); // slant
            target.x = (mid.x + d.x) / 2;
            target.y = (mid.y + d.y) / 2;
        }
        else {
            doLineThang(n, g2, d, dm);
            target.x = (dm.x + d.x) / 2;
            target.y = (dm.y + d.y) / 2;
        }

        // banned node
        if (n.notThis) {
            // put a red circle on the line here
            g.setColor(Color.red);
            int r = 4;
            if (chosenPath) r = 6;
            g.fillOval(target.x - r, target.y - r, r * 2, r * 2);
        }
        else if (n.nodeType != Node.TYPE_COMMENT && n.mom.forceMove) {
            // moves from mommy are forced: maternal tough-love
            g.setColor(Color.black);
            int r = 3;
            g.fillOval(target.x - r, target.y - r, r * 2, r * 2);
        }

        g2.setStroke(oldStroke);
    }

    private void doLineThang(Node n, Graphics2D g2, Point d, Point dm) {
        if (n.nodeType == Node.TYPE_COMMENT) {
            // draw double lines here
            int sz = 2;
            int dx = 0, dy = 0;
            if (dm.x == d.x) {
                dx = sz;
            }
            else if (dm.y == d.y) {
                dy = sz;
            }
            else {
                dy = sz;
                dx = -1;
            }
            g2.drawLine(dm.x + dx, dm.y + dy, d.x + dx, d.y + dy); // normal
            g2.drawLine(dm.x - dx, dm.y - dy, d.x - dx, d.y - dy); // normal
        }
        else {
            g2.drawLine(dm.x, dm.y, d.x, d.y); // normal
        }
    }
}
