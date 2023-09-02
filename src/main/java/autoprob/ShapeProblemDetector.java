package autoprob;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;

import java.awt.*;
import java.util.Properties;

public class ShapeProblemDetector extends ProblemDetector {

    // prev is the problem position. kar is the mistake position. node represents prev.
    public ShapeProblemDetector(KataAnalysisResult prev, KataAnalysisResult mistake, Node node, Properties props) throws Exception {
        super(prev, mistake, node, props);
    }

    public void detectProblem(KataBrain brain, boolean forceDetect) throws Exception {
        //TODO
        validProblem = true;
        makeProblem();
    }

    protected void makeProblemStones() {
        // copy all stones from node board
        var b = problem.board.board;
        var src = node.board.board;

        // expand flood outwards from move
        Node child = (Node) node.babies.get(0);
        Point lastMove = child.findMove();

        sparseFlood(lastMove, b, src, 2);
    }

    public boolean isOnboard(int x, int y) {
        return !(x < 0 || y < 0 || x >= 19 || y >= 19);
    }

    private void sparseFlood(Point p, Intersection[][] dest, Intersection[][] src, int dist) {

        for (int x = p.x - dist; x <= p.x + dist; x++)
            for (int y = p.y - dist; y <= p.y + dist; y++) {
                if (!isOnboard(x, y)) continue;
                if (src[x][y].isEmpty()) continue;
                if (!dest[x][y].isEmpty()) continue;
                dest[x][y] = src[x][y];
                // recurse
                sparseFlood(new Point(x, y), dest, src, dist);
            }
    }
}
