package autoprob.go;

import java.awt.Point;
import java.util.Enumeration;
import java.util.Vector;

public class Board implements Cloneable {
    public Intersection board[][] = new Intersection[19][19];
    public int          boardX    = 19;
    public int          boardY    = 19;
    /** Vector<Point> of dead stone locations */
    public Vector    deathList = new Vector();
    private boolean     killed    = false;

    public Board() {
        // create a board record: make 19x19 even if use less
        for (int i = 0; i < 19; i++)
            for (int j = 0; j < 19; j++)
                board[i][j] = new Intersection(Intersection.EMPTY);
    }

    public Object clone() throws CloneNotSupportedException {
        Board newObject = (Board) super.clone();

        newObject.board = new Intersection[19][19];
        // make copies of the intersections
        for (int i = 0; i < 19; i++)
            for (int j = 0; j < 19; j++)
                newObject.board[i][j] = new Intersection(board[i][j]);

        return newObject;
    }

    // returns true if point in board
    public boolean inBoard(int x, int y) {
        return !(x < 0 || y < 0 || x >= boardX || y >= boardY);
    }

    // ///////////////////// GO LOGIC SECTION ////////////////////////
    // deals with groups, live or dead, etc.

    // recursively add like stones
    void makeGroup(int x, int y, Vector group, int stone) {
        if (x < 0 || y < 0 || x >= boardX || y >= boardY)
            return;
        if (board[x][y].stone != stone)
            return;
        for (Enumeration e = group.elements(); e.hasMoreElements();) {
            Point p = (Point) e.nextElement();
            if (p.x == x && p.y == y)
                return; // already in list
        }
        group.addElement(new Point(x, y));
        // System.out.println(x + " " + y);
        makeGroup(x - 1, y, group, stone);
        makeGroup(x, y + 1, group, stone);
        makeGroup(x + 1, y, group, stone);
        makeGroup(x, y - 1, group, stone);
    }

    // counts libs of one square
    int squareLibs(int x, int y) {
        int libs = 0;
        if (x >= 1)
            libs += board[x - 1][y].isEmpty() ? 1 : 0;
        if (y >= 1)
            libs += board[x][y - 1].isEmpty() ? 1 : 0;
        if (x <= (boardX - 2))
            libs += board[x + 1][y].isEmpty() ? 1 : 0;
        if (y <= (boardY - 2))
            libs += board[x][y + 1].isEmpty() ? 1 : 0;
        return libs;
    }

    // counts libs of one group
    // counts some twice -- that's fine, only a death check
    int countLibs(Vector group) {
        int libs = 0;
        for (Enumeration e = group.elements(); e.hasMoreElements();) {
            Point p = (Point) e.nextElement();
            libs += squareLibs(p.x, p.y);
        }
        // System.out.println("libs: " + libs);System.out.println();
        return libs;
    }

    // return true if given group is dead
    boolean checkDeadGroup(int x, int y) {
        if (x < 0 || y < 0 || x >= boardX || y >= boardY)
            return false;
        if (board[x][y].isEmpty())
            return false;
        Vector group = new Vector();
        makeGroup(x, y, group, board[x][y].stone);
        int libs = countLibs(group);
        return libs == 0;
    }

    // return true if dead
    boolean removeDeadGroup(int x, int y, int stone, Vector deathList) {
        if (!inBoard(x, y))
            return false;
        if (board[x][y].stone != stone)
            return false; // wrong color
        if (!checkDeadGroup(x, y))
            return false;
        Vector group = new Vector();
        makeGroup(x, y, group, board[x][y].stone);
        for (Enumeration e = group.elements(); e.hasMoreElements();) {
            Point p = (Point) e.nextElement();
            board[p.x][p.y].setStone(Intersection.EMPTY);
            deathList.add(p); // they're dead too
        }
        return true;
    }
    
    private boolean validMove(int x, int y) {
        return board[x][y].stone == Intersection.EMPTY;
    }

    // ///////////////////// END GO LOGIC SECTION ////////////////////////

    // return true if legal move
    public boolean makeMove(int x, int y, int stone) {
        if (!inBoard(x, y))
            return false;

//        if (glob.searchMode) {
//            if (board[x][y].stone == stone) {
//                // rotate insertion
//                stone = (stone + 1) % 4;
//                glob.setToMove(stone);
//            }
//            board[x][y].setStone(stone);
//            return true;
//        }

        if (!validMove(x, y))
            return false;

        board[x][y].setStone(stone);
        boolean wedead = checkDeadGroup(x, y);
        int enemy = (stone % 2) + 1;
        deathList = new Vector(); // remember what we kill. those who are about to die, i salute you.
        if (removeDeadGroup(x - 1, y, enemy, deathList))
            killed = true;
        if (removeDeadGroup(x, y - 1, enemy, deathList))
            killed = true;
        if (removeDeadGroup(x + 1, y, enemy, deathList))
            killed = true;
        if (removeDeadGroup(x, y + 1, enemy, deathList))
            killed = true;
        if (wedead && !killed) {
            board[x][y].setStone(Intersection.EMPTY);
            System.out.println("illegal move");
            return false;
        }

        return true;
    }

    public boolean isKilled() {
        return killed;
    }

    public String toString() {
        StringBuffer sb = new StringBuffer();
        for (int j = 0; j < 19; j++) {
            if (j > 0) sb.append('\n');
            for (int i = 0; i < 19; i++) {
                int stn = board[i][j].stone;
                if (stn == Intersection.BLACK) sb.append('X');
                else if (stn == Intersection.WHITE) sb.append('O');
                else sb.append('.');
            }
        }
        return sb.toString();
    }

    /**
     * if this spot is surround by opponent stones everywhere
     * but one intersection, it's part of a ko
     * @param p
     * @return
     */
    public boolean isKo(Point p) {
        int stone = board[p.x][p.y].stone;
        if (stone == Intersection.EMPTY) return false;
        int opp = (stone % 2) + 1;
        // all neighbors but one must be opponent stones
        int empty = 0;
        int x, y;
        x = p.x - 1; y = p.y;
        if (inBoard(x, y) && board[x][y].stone != opp) empty++;
        x = p.x + 1; y = p.y;
        if (inBoard(x, y) && board[x][y].stone != opp) empty++;
        x = p.x; y = p.y - 1;
        if (inBoard(x, y) && board[x][y].stone != opp) empty++;
        x = p.x; y = p.y + 1;
        if (inBoard(x, y) && board[x][y].stone != opp) empty++;
        return empty == 1;
    }
    
    /**
     * checks if empty intersection appropriate for ko, all covered by same stones
     */
    public boolean isKoShape(Point p) {
        int stone = board[p.x][p.y].stone;
        if (stone != Intersection.EMPTY) return false;
        int stn = -1;
        // all neighbors but one must be opponent stones
        int x, y;
        x = p.x - 1; y = p.y;
        if (inBoard(x, y)) {
        	if (board[x][y].stone == Intersection.EMPTY) return false;
        	if (stn == -1) stn = board[x][y].stone;
        	if (board[x][y].stone != stn) return false;
        }
        x = p.x + 1; y = p.y;
        if (inBoard(x, y)) {
        	if (board[x][y].stone == Intersection.EMPTY) return false;
        	if (stn == -1) stn = board[x][y].stone;
        	if (board[x][y].stone != stn) return false;
        }
        x = p.x; y = p.y - 1;
        if (inBoard(x, y)) {
        	if (board[x][y].stone == Intersection.EMPTY) return false;
        	if (stn == -1) stn = board[x][y].stone;
        	if (board[x][y].stone != stn) return false;
        }
        x = p.x; y = p.y + 1;
        if (inBoard(x, y)) {
        	if (board[x][y].stone == Intersection.EMPTY) return false;
        	if (stn == -1) stn = board[x][y].stone;
        	if (board[x][y].stone != stn) return false;
        }
        return true;
    }

    /**
     * remove any markup from the board, like squares and triangles
     */
    public void removeMarkup() {
        for (int i = 0; i < 19; i++)
            for (int j = 0; j < 19; j++) {
                board[i][j].markup = Intersection.MARK_NONE;
                board[i][j].text = null;
            }
    }

    // diff from other board
	public int deltaBoard(Board board2) {
		int cnt = 0;
        for (int i = 0; i < 19; i++)
            for (int j = 0; j < 19; j++)
                if (board2.board[i][j].stone != board[i][j].stone)
                	cnt++;
        return cnt;
	}
}
