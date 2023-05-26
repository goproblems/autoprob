package autoprob.go.vis.atlas;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import autoprob.go.Node;

public class Atlas2D extends Atlas {
    public Atlas2D(Node rootNode) {
        super(rootNode);
    }

    public void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // draw smoothly scaled images
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
          
        // first draw the connecting lines -- we'll draw stones over them
        LineRecurser lineRec = new LineRecurser2D(this, g);
        rootNode.generalRecurse(lineRec);

        g.setColor(Color.black);
        PaintRecurser2D paintRec = new PaintRecurser2D(this, g2);
        rootNode.generalRecurse(paintRec);
    }
}