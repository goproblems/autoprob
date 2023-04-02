package autoprob.go.vis.atlas;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;

import autoprob.go.Node;
import autoprob.go.parse.NodeRecurser;

// draw lines between nodes
class LineRecurser extends NodeRecurser {
    protected Graphics g;
    protected final Atlas atlas;

    public LineRecurser(Atlas atlas, Graphics g) {
        this.g = g;
        this.atlas = atlas;
    }

    private void thickLine(int x1, int y1, int x2, int y2) {
        g.drawLine(x1, y1, x2, y2);
        g.drawLine(x1 + 1, y1, x2 + 1, y2);
        g.drawLine(x1, y1 + 1, x2, y2 + 1);
    }

    public void action(Node n) {
        // draw a line to mommy
        if (n.mom != null) {
            Point d = atlas.nodeDrawPos(n);
            Point dm = atlas.nodeDrawPos(n.mom);
            Point target = new Point();
            // System.out.println("pos: " + Integer.toString(dm.x) + ", " +
            // Integer.toString(dm.y));
            if (n.searchForTheTruth())
                g.setColor(Color.green);
            else
                g.setColor(Color.red);
            // if there's a big vertical drop, we don't want to make a
            // slanted line all the way, so we drop vertically until just
            // before this node
            if (n.atlasY >= n.mom.atlasY + 2) {
                Point mid = atlas.atlasDrawPos(n.depth - 1, n.atlasY - 1);
                thickLine(dm.x, dm.y, mid.x, mid.y); // drop
                thickLine(mid.x, mid.y, d.x, d.y); // slant
                target.x = (mid.x + d.x) / 2;
                target.y = (mid.y + d.y) / 2;
            }
            else {
                thickLine(dm.x, dm.y, d.x, d.y); // normal
                target.x = (dm.x + d.x) / 2;
                target.y = (dm.y + d.y) / 2;
            }

            // banned node
            if (n.notThis) {
                // put a red circle on the line here
                g.setColor(Color.red);
                int r = 4;
                g.fillOval(target.x - r, target.y - r, r * 2, r * 2);
            }
            else if (n.nodeType != Node.TYPE_COMMENT && n.mom.forceMove) {
                // moves from mommy are forced: maternal tough-love
                g.setColor(Color.black);
                int r = 3;
                g.fillOval(target.x - r, target.y - r, r * 2, r * 2);
            }
        }
    }
}
