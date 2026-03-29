package autoprob.api;

import autoprob.go.Intersection;

import java.awt.Point;

/**
 * Represents a rectangular area on the board using A1 coordinates.
 * A1 coordinate system: columns A-T (skip I), rows 1-19, A1 = bottom-left, T19 = top-right.
 */
public class RectangleArea {
    public String min;
    public String max;

    public RectangleArea() {}

    public RectangleArea(String min, String max) {
        this.min = min;
        this.max = max;
    }

    /**
     * Check if a board point (0-indexed, y=0 is top row) is within this rectangle area.
     */
    public boolean contains(Point p) {
        Point minP = Intersection.gtp2point(min);
        Point maxP = Intersection.gtp2point(max);
        // A1's "min" has smaller row number → larger y after gtp2point (y = 19 - row),
        // so minP.y > maxP.y. Use Math.min/max to get the actual coordinate bounds.
        int x0 = Math.min(minP.x, maxP.x);
        int x1 = Math.max(minP.x, maxP.x);
        int y0 = Math.min(minP.y, maxP.y);
        int y1 = Math.max(minP.y, maxP.y);
        return p.x >= x0 && p.x <= x1 && p.y >= y0 && p.y <= y1;
    }

    @Override
    public String toString() {
        return min + "-" + max;
    }
}
