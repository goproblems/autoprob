package autoprob.go.vis.atlas;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.RenderingHints;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.MoveAction;
import autoprob.go.vis.Pix;

public class PaintRecurser2D extends PaintRecurser {
    private static AlphaComposite acNotFav = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .55f);
//    private static final Color pathCol = new Color(0x00EEE21A);
    Image commentImg;

    public PaintRecurser2D(Atlas atlas, Graphics g) {
        super(atlas, g);
        if (commentImg == null) {
            commentImg = Pix.loadResourceImage("atlas/comment.png");
        }
    }

    public void action(Node n) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Point d = atlas.nodeDrawPos(n);
        int x = d.x - atlas.sz / 4;
        int y = d.y - atlas.sz / 4;

        // current move? if so, indicate this
        if (atlas.currentNode == n) {
            g.setColor(Color.yellow);
            int r = atlas.sz / 4 + 3;
            if (n.result == Intersection.RIGHT)
                r += 3;
            g.fillOval(d.x - r, d.y - r, r * 2, r * 2);
        }

        Composite origComposite = g2.getComposite();
        boolean chosenPath = false; //n.onChosenPath(atlas.globals.getCurNode());
        if (!chosenPath)
            g2.setComposite(acNotFav);

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
        
        // user comment?
        int userComments = 0;
        int splatter = 0;
            // check if we gots us a live one
//        for (int i = 0; i < n.userComments.size(); i++) {
//            UserComment uc = (UserComment) n.userComments.get(i);
//            if (uc.alive || globals.deactivatedComments) {
//                userComments++;
//                int delta = globals.userCommentCount - uc.commentIndex - 1;
//                if (delta < 8)
//                    splatter |= (1 << delta);
//            }
//        }
//        if (userComments > 0) {
//            // we draw a 'T' sized according to number of comments
//            Composite oc = g2.getComposite();
//            g2.setComposite(chosenPath ? Pix.ac75 : Pix.ac50);
//            int sz = 12;
//            sz += Math.sqrt(userComments - 1) * 3;
//            int offset = 7 + (16 - sz) / 2;
//            g2.drawImage(commentImg, x + offset, y + offset, sz, sz, null);
//            g2.setComposite(oc);
//            
//            // if we have recent comments, indicate that
//            // concentric boxes
//            int show = 3;
//            for (int i = 0; i < show; i++) {
//                if ((splatter & (1 << i)) == 0) continue;
//                int cx = (int) (x + offset + sz * 0.9);
//                int cy = (int) (y + offset + sz * 0.9);
//                g2.setColor(Color.BLACK);
//                int ss = i * 2 + 2;
//                g2.drawRect(cx - ss / 2, cy - ss / 2, ss, ss);
//            }
//        }

        // numbers
//        if (globals.pathMode && n.mom != null) {
//            if (((n.mom.getPathCount() != n.getPathCount()) || (n.mom.babies.size() > 1))
//                    && n.getPathCount() > 0) {
////                if (n.mom.babies.size() > 0 && n.getPathCount() > 0) {
//                g.setColor(Color.BLACK);
////                g.setColor(pathCol);
//                String s = Integer.toString(n.getPathCount());
//                g.drawString(s, d.x + 5, d.y + 13);
//            }
//        }

        g2.setComposite(origComposite);
    }
}
