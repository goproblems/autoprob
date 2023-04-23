package autoprob.go.vis;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Checkbox;
import java.awt.Color;
import java.awt.Composite;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.TexturePaint;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Enumeration;
import java.util.List;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;

public class BasicGoban2D extends BasicGoban {
    private TexturePaint  texpaint;
    private Color         sideTopColor     = new Color(0xFF8E7145);
    private Color         sideBotColor     = new Color(0xFF655133);

    public static Image   shadowImg        = Pix.loadResourceImage("v2/shadow2.png");
    public static Image   boardImg         = Pix.loadResourceImage("v2/board.png");
    public static Image   lastMoveWhiteImg = Pix.loadResourceImage("v2/lastmovewht.png");
    public static Image   lastMoveBlackImg = Pix.loadResourceImage("v2/lastmoveblk.png");
    
    private AlphaComposite alphaLines = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f);
	private List<Double> ownership;
    private static final BasicStroke chosenStroke   = new BasicStroke(2.0f, BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND);

	public BasicGoban2D(Node node, List<Double> ownership) {
		super(node);
		this.ownership = ownership;

        BufferedImage image = new BufferedImage(boardImg.getWidth(null), boardImg.getHeight(null),
                BufferedImage.TYPE_INT_RGB);
        image.createGraphics().drawImage(boardImg, 0, 0, null);
        texpaint = new TexturePaint(image, new Rectangle(0, 0, image.getWidth(), image.getHeight()));
        
        setDoubleBuffered(false);
	}

    public void paintComponent(Graphics g) {
        if (offImage == null)
            return;

        Board board = node.board;
        
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // draw background
        drawBoard(g2);

        drawShadows(g2, board);
        drawSquares(g2, board);

        drawColumnHeads(g2, 0, 0);
        drawColumnHeads(g2, 0, sz * viewable.height + sz);

        drawRowHeads(g2, board, 0, 0 + sz);
        drawRowHeads(g2, board, sz * viewable.width + sz, sz);

        drawBoardSides3D(g2);
    }

    private void drawBoardSides3D(Graphics2D g2) {
        int ex = sz * (viewable.width + 2);
        int ey = sz * (viewable.height + 2);

        Polygon p = new Polygon();
        p.addPoint(0, ey);
        p.addPoint(ex, ey);
        p.addPoint(ex + SHADOWSIZE, ey + SHADOWSIZE);
        p.addPoint(SHADOWSIZE, ey + SHADOWSIZE);
        p.addPoint(0, ey);
        // prepare drawing style
        GradientPaint grad = new GradientPaint(0, ey, sideTopColor, 0, ey + SHADOWSIZE, sideBotColor);
        g2.setPaint(grad);
        g2.fillPolygon(p);

        p = new Polygon();
        p.addPoint(ex, 0);
        p.addPoint(ex, ey);
        p.addPoint(ex + SHADOWSIZE, ey + SHADOWSIZE);
        p.addPoint(ex + SHADOWSIZE, SHADOWSIZE);
        p.addPoint(ex, 0);
        // prepare drawing style
        grad = new GradientPaint(ex, 0, sideTopColor, ex + SHADOWSIZE, 0, sideBotColor);
        g2.setPaint(grad);
        g2.fillPolygon(p);
    }

    private void drawSquares(Graphics2D g2, Board board) {
        // draw pieces
        AffineTransform saveXform = g2.getTransform();
        for (int i = 0; i < 19; i++)
            for (int j = 0; j < 19; j++)
                if (viewable.contains(i, j)) {
                    AffineTransform at = new AffineTransform();
                    at.translate(sz + sz * (i - viewable.x), sz + sz * (j - viewable.y));
                    g2.transform(at);
                    drawSquare(g2, board, i, j);
                    g2.setTransform(saveXform);
                }
    }

    private void drawShadows(Graphics2D g2, Board board) {
        // draw shadows
        int ix = (int) (shadowImg.getWidth(null) * (sz / 32.0));
        for (int i = 0; i < 19; i++)
            for (int j = 0; j < 19; j++)
                if (viewable.contains(i, j)) {
                    Intersection inter = board.board[i][j];
                    if (inter == null)
                        continue;
                    int stone = inter.stone;
                    if (!(stone == Intersection.WHITE || stone == Intersection.BLACK || stone == Intersection.MURKY))
                        continue;
                    final int SHADOW_OFFSET = 0;

                    g2.drawImage(shadowImg, SHADOW_OFFSET + sz + sz * (i - viewable.x), SHADOW_OFFSET + sz + sz
                            * (j - viewable.y), ix, ix, this);
                }
    }

    private void drawBoard(Graphics2D goff2) {
        goff2.setPaint(Pix.colBack);
        goff2.fillRect(0, 0,  sz * viewable.width,  sz * viewable.height);
        int ex = sz * (viewable.width + 2);
        int ey = sz * (viewable.height + 2);

        goff2.setPaint(texpaint);

        goff2.fillRect(0, 0, ex, ey);
        //        goff2.fillRect(0, 0, offscreenImage.getWidth() - SHADOWSIZE, 
        //                offscreenImage.getHeight() - SHADOWSIZE);
    }

    void centerSquareText(Graphics g, String s, int x, int y) {
        int fx = sz / 2, fy = sz / 2;
        FontMetrics fm = getFontMetrics(g.getFont());
        fx -= fm.stringWidth(s) / 2;
        fy += (fm.getAscent()) / 2;
        // put it on the temp image
        g.drawString(s, x + fx, y + fy);
    }

    // draw letters across top 'n bottom
    public void drawColumnHeads(Graphics g, int sx, int sy) {
        g.setColor(Color.black);
        sx += sz;
        for (int x = 0; x < 19; x++) {
            if (x < viewable.x)
                continue; // not in sight
            if (x >= viewable.x + viewable.width)
                continue; // gone
            // make a letter according to where we are on the board
            String s = "";
            char c = 'A';
            // skip over 'i'
            c = (char) ('A' + x + ((x >= 'I' - 'A') ? 1 : 0));
            s = Character.toString(c);
            centerSquareText(g, s, sx, sy);
            sx += sz; // move along
        }
    }

    // draw numbers down sides
    public void drawRowHeads(Graphics g, Board board, int sx, int sy) {
        for (int y = 0; y < board.boardY; y++) {
            if (y < viewable.y)
                continue; // not in sight
            if (y >= viewable.y + viewable.height)
                continue; // gone
            // make a number according to where we are on the board
            String s = "" + (board.boardY - y);
            centerSquareText(g, s, sx, sy);
            sy += sz; // move along
        }
    }

    // draw one square of a board, which in reality looks like a plus sign since
    // go works on intersections
    void drawSquare(Graphics2D offscreen, Board board, int x, int y) {
        Intersection inter = board.board[x][y];
        Node n = node;

        if (inter == null)
            return;

        int stone = inter.stone;
        int mid = sz / 2;
        String nodeText = inter.text;
        // path?
        boolean hastext = (nodeText != null && nodeText.length() > 0);

        // Image target = DropShadow.getDropShadow(stone == Globals.BLACK ?
        // ga.imgBlack : ga.imgWhite);

        switch (stone) {
            case Intersection.BLACK:
                offscreen.drawImage(Pix.imgBlack, 0, 0, this);
                break;
            case Intersection.WHITE:
                offscreen.drawImage(Pix.imgWhite, 0, 0, this);
                break;
            case Intersection.EMPTY:
                // draw stones captured last move
                break;
        }

        // draw lines and stuff
        if ((stone == Intersection.EMPTY || stone == Intersection.MURKY) && !hastext) {
            if (stone == Intersection.MURKY)
                offscreen.setColor(Color.gray);
            else
                offscreen.setColor(Color.black);

            Composite origComposite = offscreen.getComposite();
            offscreen.setComposite(alphaLines);

            // horizontal
            int sln = 0, eln = sz;
            if (x == 0)
                sln = mid;
            if (x == board.boardX - 1) {
                eln = mid;
                eln++;
            }
            else
                eln--;
            if (x == 0 && (y > 0) && (y < board.boardY - 1)) sln += 2;
            offscreen.drawLine(sln, mid, eln, mid);
            if (THICKEDGES && (y % (board.boardY - 1) == 0))
                offscreen.drawLine(sln, mid + 1, eln, mid + 1);
            // vertical
            sln = 0;
            eln = sz;
            if (y == 0)
                sln = mid;
            if (y == (board.boardY - 1))
                eln = mid;
            else
                eln--;
            if (y == 0) sln += 2;
            offscreen.drawLine(mid, sln, mid, eln);
            boolean twoLines = (THICKEDGES && (x % (board.boardX - 1) == 0));
            if (twoLines)
                offscreen.drawLine(mid + 1, sln, mid + 1, eln);
            // cover top for lame JVM's -- some don't draw the first pixel for
            // who knows what reason
//            if (sln == 0)
//                offscreen.drawLine(mid, 0, mid + (twoLines ? 1 : 0), 0);
            // star point?
            if (Intersection.isStarPoint(x, y, board.boardX, board.boardY)) {
                offscreen.fillOval(mid - starsz / 2, mid - starsz / 2, starsz, starsz);
            }
            offscreen.setComposite(origComposite);

            // colored marker?
            if (inter.col != null) {
                offscreen.setColor(inter.col);
                offscreen.fillOval(mid - starsz / 2, mid - starsz / 2, starsz, starsz);
                // if this represents our chosen path, add an indicator
                Node chosen = n.favoriteSon();
                if (chosen != null) {
                    Point p = chosen.findMove();
                    if (p != null && p.x == x && p.y == y) {
                        // chosen one
                        int z = starsz + 6;
                        Stroke stroke = offscreen.getStroke();
                        offscreen.setStroke(chosenStroke);
                        offscreen.drawOval(mid - z / 2 - 1, mid - z / 2 - 1, z + 1, z + 1);
                        offscreen.setStroke(stroke);
                    }
                }
            }
        }

        if (inter.col != null) {
            offscreen.setColor(inter.col);
            offscreen.fillOval(mid - starsz / 2, mid - starsz / 2, starsz, starsz);
        }
        
        drawOwnership(offscreen, board, x, y);

        // draw triangle markup?
        if (inter.markup == Intersection.MARK_TRIANGLE) {
            offscreen.setColor(stone == Intersection.BLACK ? Color.white : Color.black);
            double padFrac = 0.3;
            int padding = (int) (sz * padFrac);
            //            int sqWid = sz - (int) (sz * padFrac * 2.0);
            // bottom
            offscreen.drawLine(padding, sz - padding, sz - padding, sz - padding);
            // left
            offscreen.drawLine(padding, sz - padding, mid, padding - 1);
            // right
            offscreen.drawLine(mid, padding - 1, sz - padding, sz - padding);
        }

        // draw square markup?
        if (inter.markup == Intersection.MARK_SQUARE) {
            offscreen.setColor(stone == Intersection.BLACK ? Color.white : Color.black);
            double padFrac = 0.3;
            int padding = (int) (sz * padFrac), sqWid = sz - (int) (sz * padFrac * 2.0);
            offscreen.drawRect(padding, padding, sqWid, sqWid);
        }

        // draw last move marker?
        Point lastMove = n.findMove();
        if (lastMove != null && stone != Intersection.EMPTY && lastMove.x == x && lastMove.y == y) {
            Image img = (stone == Intersection.BLACK ? lastMoveBlackImg : lastMoveWhiteImg);
            int ix = (int) (img.getWidth(null) * (sz / 32.0));
            offscreen.drawImage(img, sz / 2 - ix / 2, sz / 2 - ix / 2, ix, ix, null);
        }
        
        // ko indicator?
        Point ko = n.getKo();
        if (ko != null && !(ko.x == x && ko.y == y))
            ko = null;
        if (ko != null) {
            offscreen.setColor(Color.GRAY);
            double padFrac = 0.25;
            int padding = (int) (sz * padFrac), sqWid = sz - (int) (sz * padFrac * 2.0);
            offscreen.drawRect(padding, padding, sqWid, sqWid);
        }

        // draw ghost? (move hover)
        boolean checkValid4Ghost = true;
        Checkbox cur = null;
        if ((!checkValid4Ghost || validMove(x, y)) && ghost.x == x && ghost.y == y && ko == null) {
            drawGhostMove(offscreen, cur);
        }

        // draw extra text?
        if (hastext) {
            if (stone == Intersection.BLACK)
                offscreen.setColor(Color.white);
            else
                offscreen.setColor(Color.black);
//            if (inter.col != null && glob.getMode() == Globals.MODENAVIGATE)
//                offscreen.setColor(inter.col);
            centerSquareText(offscreen, nodeText, 0, 0);
        }
    }

    private void drawOwnership(Graphics2D offscreen, Board board, int x, int y) {
    	if (ownership == null)
    		return;
    	
    	double o = ownership.get(y * 19 + x);
    	if (Math.abs(o) < 0.2) return;
    	
        int mid = sz / 2;

        float f = (float) (1 - (o + 1.0) / 2.0);
        Color c = new Color(f, f, f);
        offscreen.setColor(c);
        Stroke stroke = offscreen.getStroke();
        offscreen.setStroke(chosenStroke);
        int z = starsz + 12;
        offscreen.drawOval(mid - z / 2 - 1, mid - z / 2 - 1, z + 1, z + 1);
        offscreen.setStroke(stroke);
	}

	/**
     * @param offscreen
     * @param cur
     * @param ep
     */
    private void drawGhostMove(Graphics2D offscreen, Checkbox cur) {
        // if we're in edit mode, what we have selected affects what we draw
        int ghostStone = node.getToMove();
        boolean drawCross = false;
//        if (glob.editMode) {
//            // choose stone based on what they've selected as an edit button
//            if (cur == ep.ebBlack)
//                ghostStone = Globals.BLACK;
//            else if (cur == ep.ebWhite)
//                ghostStone = Globals.WHITE;
//            else if (cur == ep.ebSetup) {
//                ghostStone = (glob.shiftDown /*^ capsDown*/) ? Globals.WHITE : Globals.BLACK;
//                drawCross = true;
//            }
//            else {
//                ghostStone = -1;
//                drawCross = true;
//            }
//        }
        if (ghostStone >= 0)
            offscreen.drawImage(ghostStone == Intersection.BLACK ? Pix.ghostBlack : Pix.ghostWhite, 0, 0, this);
        if (drawCross) {
            // draw a cross
            offscreen.setColor(Color.black);
            offscreen.drawLine(2, 2, sz - 2, sz - 2);
            offscreen.drawLine(2, sz - 2, sz - 2, 2);
        }
    }
}
