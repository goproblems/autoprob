package autoprob.go;

// basic class for storing one intersection in a board position
// there can be a stone in an intersection
// intersections can have markup, such as triangles or numbers

import java.awt.Color;
import java.awt.Point;

public class Intersection {
    public static final int EMPTY         = 0;
    public static final int BLACK         = 1;
    public static final int WHITE         = 2;
    public static final int MURKY         = 3;

    public static final int MARK_NONE     = 0;
    public static final int MARK_TRIANGLE = 1;
    public static final int MARK_SQUARE   = 2;
    
    public static final int INDETERMINATE = 0;
    public static final int RIGHT = 1;
    public static final int WRONG = 2;

    public int              stone         = EMPTY;
    public int              markup        = MARK_NONE;
    //  for the pretty navigating solution stuff
    public Color            col           = null;
    public String           text          = null;

    public Intersection() {
    }

    public Intersection(int stn) {
        stone = stn;
    }

    public Intersection(Intersection src) {
        stone = src.stone;
        markup = src.markup;
        text = src.text;
        col = src.col;
    }

    public void setStone(int stn) {
        stone = stn;
    }

    public void setText(String s) {
        text = s;
    }

    public void setColor(Color c) {
        col = c;
    }

    public void setMarkup(int markup) {
        this.markup = markup;
    }

    public void clear() {
        stone = EMPTY;
        clearFluff();
    }

    public boolean isEmpty() {
        return stone == EMPTY;
    }

    public static boolean isStarPoint(int x, int y, int boardX, int boardY) {
        if (boardX == 19 && boardY == 19) {
            if ((x == 3 || x == 9 || x == 15) && (y == 3 || y == 9 || y == 15))
                return true;
            return false;
        }
        if (boardX == 13 && boardY == 13) {
            if ((x == 3 || x == 6 || x == 9) && (y == 3 || y == 6 || y == 9))
                return true;
            return false;
        }
        if (boardX == 9 && boardY == 9) {
            if ((x == 2 || x == 6) && (y == 2 || y == 6))
                return true;
            if (x == 4 && y == 4)
                return true;
        }
        return false;
    }

    public void clearFluff() {
        text = null;
        markup = MARK_NONE;
        col = null;
    }
    
    public static String toGTPloc(int x, int y, int boardY) {
    	if (x == 19 || y == 19)
    		return "pass";
        // skip over 'i'
        char c = (char) ('A' + x + ((x >= 'I' - 'A') ? 1 : 0));
        String sx = Character.toString(c);
        String sy = "" + (boardY - y); // upside down
        return sx + sy;
    }

    public static String toGTPloc(int x, int y) {
        return toGTPloc(x, y, 19);
    }

    public static String toSGFcoord(int x, int y) {
    	String sx = Character.toString('a' + x);
    	String sy = Character.toString('a' + y);
    	return sx + sy;
    }
    
    public static Point gtp2point(String gpt) {
    	if (gpt.toLowerCase().equals("pass"))
    		return new Point(19, 19);
    	// G18, pass etc
    	char cx = gpt.charAt(0);
		int x = cx - 'A';
		if (cx > 'I') x--; // we skip 'I'
    	int y = 19 - Integer.parseInt(gpt.substring(1));
    	return new Point(x, y);
    }

    public static String color2katagoname(int color) {
    	if (color == BLACK)
    		return "B";
    	if (color == WHITE)
    		return "W";
        throw new RuntimeException("invalid color " + color);
    }

    public static String color2name(int color) {
        if (color == BLACK)
            return "black";
        if (color == WHITE)
            return "white";
        throw new RuntimeException("invalid color " + color);
    }

    public static String color2name(int color, boolean capFirstLetter) {
        if (!capFirstLetter)
            return color2name(color);
        if (color == BLACK)
            return "Black";
        if (color == WHITE)
            return "White";
        throw new RuntimeException("invalid color " + color);
    }
} // Intersection
