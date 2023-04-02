package autoprob.go.vis;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.AreaAveragingScaleFilter;
import java.awt.image.CropImageFilter;
import java.awt.image.FilteredImageSource;
import java.awt.image.ReplicateScaleFilter;
import java.net.URL;

import javax.swing.ImageIcon;

import autoprob.go.vis.widget.GhostFilter;


public class Pix {
	public static Image imgBoard;
	public static Image imgWhite;
	public static Image imgBlack;
	public static Image srcBoard;
	public static Image srcWhite;
	public static Image srcBlack;
	public static Image sBoard;
	public static Image sWhite;
	public static Image sBlack;
	public static Image srcghostWhite;
	public static Image srcghostBlack;
	public static Image ghostWhite;
	public static Image ghostBlack;
	public static Image sghostWhite;
	public static Image sghostBlack;
	public static Image smileyImg;
	public static Image ebiCut;
	public static Image ebiSetup;
	public static Image ebiBlack;
	public static Image ebiWhite;
	public static Image ebiLabel;
	public static Image ebiTriangle;
	public static Image ebiSquare;
	public static Image ebiRight;
	public static Image ebiNot;
	public static Image ebiForce;
	public static Image ebiExpand;
	public static Image ebiContract;
	public static Image ebiChoice;

	public static Color colBack = new Color(0x00bbbbbb);
	public static Color colWood = new Color(0x00E3B268);
	public static Color colShadow = new Color(130, 79, 50);
	public static Color colFreshWrongPath = new Color(0x00B04313);

    public static AlphaComposite ac75 = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .75f);
    public static AlphaComposite ac50 = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, .5f);

    static final String EDIT_PATH = "edit/";

	public static void LoadAll()
	{
        // load images from site
        srcBoard = loadImage("board.gif");
        srcWhite = loadImage("whitetrans.gif");
        srcWhite = loadImage("v2/white.png");
        srcBlack = loadImage("blacktrans.gif");
        srcBlack = loadImage("v2/black.png");
        smileyImg = loadImage("smile.gif");
        
        sBoard = srcBoard;
        // temporarily set to large
        sWhite = srcWhite;
        sBlack = srcBlack;
        
        ebiCut = loadImage(EDIT_PATH + "cross.gif");
        ebiSetup = loadImage(EDIT_PATH + "harmony.gif");
        ebiBlack = loadImage(EDIT_PATH + "blackbutt.gif");
        ebiWhite = loadImage(EDIT_PATH + "whitebutt.gif");
        ebiLabel = loadImage(EDIT_PATH + "lettera.gif");
        ebiTriangle = loadImage(EDIT_PATH + "triangle.gif");
        ebiSquare = loadImage(EDIT_PATH + "square.gif");
        ebiRight = loadImage(EDIT_PATH + "right.gif");
        ebiNot = loadImage(EDIT_PATH + "notthis.gif");
        ebiForce = loadImage(EDIT_PATH + "force.gif");
        ebiChoice = loadImage(EDIT_PATH + "target.gif");
        ebiExpand = loadImage(EDIT_PATH + "expand.gif");
        ebiContract = loadImage(EDIT_PATH + "contract.gif");
        //
        // // shadowImg = loadResourceImage("v2/shadow2.png");
        // shadowImg = loadImage("v2/shadow2.png");
        // // boardImg = loadImage("v2/board.png");
        // boardImg = loadResourceImage("v2/board.png");
        // lastMoveWhiteImg = loadImage("v2/lastmovewht.png");
        // lastMoveBlackImg = loadImage("v2/lastmoveblk.png");
    }
	
	public static void CreateDerivatives() {
		Toolkit toolkit = Toolkit.getDefaultToolkit();
        // make image ghosts
        srcghostWhite = toolkit.createImage(new FilteredImageSource(srcWhite.getSource(), new GhostFilter()));
        srcghostBlack = toolkit.createImage(new FilteredImageSource(srcBlack.getSource(), new GhostFilter()));
        sBoard = toolkit.createImage(new FilteredImageSource(srcBoard.getSource(), new CropImageFilter(0, 0, 16, 16)));
        sWhite = toolkit.createImage(new FilteredImageSource(srcWhite.getSource(), new ReplicateScaleFilter(16, 16)));
        sBlack = toolkit.createImage(new FilteredImageSource(srcBlack.getSource(), new ReplicateScaleFilter(16, 16)));
        
        sghostWhite = toolkit.createImage(new FilteredImageSource(sWhite.getSource(), new GhostFilter()));
        sghostBlack = toolkit.createImage(new FilteredImageSource(sBlack.getSource(), new GhostFilter()));
	}
    
    // go to 32x32
	public static void useBigImages() {
        imgWhite = srcWhite;
        imgBlack = srcBlack;
        imgBoard = srcBoard;
        ghostWhite = srcghostWhite;
        ghostBlack = srcghostBlack;
    }
    
    // go to 16x16
    public static void useSmallImages() {
        imgWhite = sWhite;
        imgBlack = sBlack;
        imgBoard = sBoard;
        ghostWhite = sghostWhite;
        ghostBlack = sghostBlack;
    }

    // go to fit
    public static void useFitImages(int sz) {
		Toolkit toolkit = Toolkit.getDefaultToolkit();
        imgWhite = toolkit.createImage(new FilteredImageSource(srcWhite.getSource(), new AreaAveragingScaleFilter(sz, sz)));
        imgBlack = toolkit.createImage(new FilteredImageSource(srcBlack.getSource(), new AreaAveragingScaleFilter(sz, sz)));
        imgBoard = toolkit.createImage(new FilteredImageSource(srcBoard.getSource(), new CropImageFilter(0, 0, sz, sz)));
        ghostWhite = toolkit.createImage(new FilteredImageSource(imgWhite.getSource(), new GhostFilter()));
        ghostBlack = toolkit.createImage(new FilteredImageSource(imgBlack.getSource(), new GhostFilter()));
    }

    public static Image loadResourceImage(String path) {
//        String loc = "img/" + path;
        String loc = path;
        java.net.URL imgURL = BasicGoban.class.getResource("/" + loc);
//      System.out.println("readin url: " + imgURL);
        if (imgURL == null)
            imgURL = BasicGoban.class.getResource("/" + loc);
        if (imgURL != null)
            return new ImageIcon(imgURL).getImage();
        else
            return new ImageIcon(path).getImage();
    }
    
    protected static Image loadImage(String loc) {
        URL res = Pix.class.getResource("/" + loc);
		Image im = Toolkit.getDefaultToolkit().createImage(res);
		return im;
    }
    
}
