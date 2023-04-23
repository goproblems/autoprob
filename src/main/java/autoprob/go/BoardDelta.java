package autoprob.go;

import java.awt.Point;

/**
 * represents the difference between a board and the starting position, including who is to move and a possible ko point
 */
public final class BoardDelta {
    public final int[] stones;
    public final int toMove;
    private final Point koPoint;
    
    public BoardDelta(int[] stones, int toMove, Point koPoint) {
        this.stones = stones;
        this.toMove = toMove;
        this.koPoint = koPoint;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof BoardDelta)) return false;
        BoardDelta bd = (BoardDelta) obj;
        if (bd.toMove != toMove) return false;

        // matching ko points?
        if ((bd.koPoint == null) ^ (koPoint == null)) return false;
        if (koPoint != null && !koPoint.equals(bd.koPoint)) return false;
        
        if (bd.stones.length != stones.length) return false;
        for (int i = 0; i < stones.length; i++)
            if (bd.stones[i] != stones[i]) return false;
        return true;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    public int hashCode() {
        int h = 37;
        for (int i = 0; i < stones.length; i++) {
            h += 37 * h + stones[i];
        }
        h += toMove;
        if (koPoint != null) h ^= koPoint.hashCode();
        return h;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#toString()
     */
    public String toString() {
        return Integer.toString(hashCode());
    }
}
