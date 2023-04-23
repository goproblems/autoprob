package autoprob.go.vis.atlas;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Point;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;
import autoprob.go.parse.NodeRecurser;
import autoprob.go.vis.Pix;


class PaintRecurser extends NodeRecurser {
    protected final Atlas atlas;
    protected final Graphics g;
//    protected final Globals globals;

    public PaintRecurser(Atlas atlas, Graphics g) {
        this.g = g;
        this.atlas = atlas;
//        this.globals = globals;
    }

    public void action(Node n) {
        Point d = atlas.nodeDrawPos(n);
        int x = d.x - atlas.sz / 4;
        int y = d.y - atlas.sz / 4;

        // current move? if so, indicate this
//        if (globals.getCurNode() == n) {
//            g.setColor(Color.yellow);
//            int r = atlas.sz / 4 + 3;
//            if (n.result == Globals.RIGHT)
//                r += 3;
//            g.fillOval(d.x - r, d.y - r, r * 2, r * 2);
//        }

        // contains a RIGHT?
        if (n.result == Intersection.RIGHT) {
            g.setColor(Color.green);
            int r = atlas.sz / 4 + 3;
            g.fillOval(d.x - r, d.y - r, r * 2, r * 2);
        }

        // CHOICE?
        if (n.isChoice) {
            g.setColor(Color.black);
            int r = atlas.sz / 4 + 5;
            g.drawOval(d.x - r, d.y - r, r * 2, r * 2);
        }

        // draw a stone for the node
        MoveAction ma = n.getMoveAction();
        if (ma != null) {

            if (ma.stone == Intersection.BLACK)
                g.drawImage(Pix.sBlack, x, y, atlas);
            else
                g.drawImage(Pix.sWhite, x, y, atlas);
            /*
             * if (ma.stone == Globals.BLACK) g.drawImage(ga.sghostBlack, x,
             * y, ga); else g.drawImage(ga.sghostWhite, x, y, ga);
             */
        }
        else {
            // draw setup
            g.drawImage(Pix.sWhite, x - 2, y - 2, atlas);
            // g.drawImage(ga.sghostWhite, x - 2, y - 2, ga);
            g.drawImage(Pix.sghostBlack, x + 2, y + 2, atlas);
        }

        // commented?
        if (n.comment != null && n.comment.length() > 0) {
            // draw a circle
            if (ma == null || ma.stone == Intersection.WHITE)
                g.setColor(Color.black);
            else
                g.setColor(Color.white);
            int r = 3;
            g.fillOval(d.x - r, d.y - r, r * 2, r * 2);
        }

        // banned nodes
        if (n.notThis) {
            /*
             * g.setColor(Color.red); int xsize = 5; g.drawLine(d.x - xsize,
             * d.y - xsize, d.x + xsize, d.y + xsize); g.drawLine(d.x -
             * xsize, d.y + xsize, d.x + xsize, d.y - xsize); g.drawLine(d.x -
             * xsize, 1 + d.y - xsize, d.x + xsize, 1 + d.y + xsize);
             * g.drawLine(d.x - xsize, 1 + d.y + xsize, d.x + xsize, 1 + d.y -
             * xsize);
             */
        }
    }
}
