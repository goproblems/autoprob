package autoprob.go;

import autoprob.katastruct.KataAnalysisResult;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.HashSet;

public class StoneGroup {
    protected static final DecimalFormat df = new DecimalFormat("0.00");

    public final int stone;
    public double ownership;
    public HashSet<Point> stones = new HashSet<>();

    public StoneGroup(int stone) {
    	this.stone = stone;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Point p : stones) {
            sb.append(Intersection.toGTPloc(p.x, p.y, 19)).append(" ");
        }
        return "StoneGroup{" +
                "stone=" + Intersection.color2katagoname(stone) +
                ", ownership=" + (ownership < 0 ? "white: " : "black: ") + df.format(ownership) +
                ", stones=" + sb +
                '}';
    }

    public void calculateOwnership(KataAnalysisResult kar) {
        // iterate through all stones, average out ownership
        double sum = 0;
        for (Point p : stones) {
            sum += kar.ownership.get(p.x + p.y * 19);
        }
        ownership = sum / stones.size();
    }
}
