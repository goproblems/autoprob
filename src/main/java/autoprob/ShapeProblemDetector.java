package autoprob;

import autoprob.go.Board;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

import java.awt.*;
import java.util.ArrayList;
import java.util.Properties;

public class ShapeProblemDetector extends ProblemDetector {

    // prev is the problem position. kar is the mistake position. node represents prev.
    public ShapeProblemDetector(KataAnalysisResult prev, KataAnalysisResult mistake, Node node, Properties props) throws Exception {
        super(prev, mistake, node, props);
    }

    public void detectProblem(KataBrain brain, boolean forceDetect) throws Exception {
        validProblem = true;
        makeProblem();

        System.out.println("validating shape problem...");

        // run katago with this new problem, make sure we really want to play here still
        boolean dbgOwn = Boolean.parseBoolean(props.getProperty("search.debug_pass_ownership", "false"));
        int rootVisits = Integer.parseInt(props.getProperty("search.root_visits"));
        var na = new NodeAnalyzer(props, dbgOwn);
        KataAnalysisResult karDeep = na.analyzeNode(brain, problem, rootVisits);

        MoveInfo topMove = karDeep.moveInfos.get(0);
        System.out.println("top move: " + topMove.extString());

        // check there is only one good top move
        double minTopMoveScoreMargin = Double.parseDouble(props.getProperty("shape.min_top_move_score_margin", "4"));
        if (karDeep.moveInfos.size() > 1) {
            MoveInfo secondMove = karDeep.moveInfos.get(1);
            System.out.println("second move: " + secondMove.extString());
            double deltaScore = topMove.scoreLead - secondMove.scoreLead;
            System.out.println("delta score: " + deltaScore);
            if (deltaScore < minTopMoveScoreMargin) {
                System.out.println("not a good shape problem, second move too good");
                if (!forceDetect) {
                    validProblem = false;
                    return;
                }
            }
        }

        // add solution to problem
        Point p = Intersection.gtp2point(topMove.move);
        Node solution = problem.addBasicMove(p.x, p.y);
        solution.result = Intersection.RIGHT;

        tryNearbyMoves(brain, topMove, na);
    }

    private void tryNearbyMoves(KataBrain brain, MoveInfo topMove, NodeAnalyzer na) throws Exception {
        // evaluate nearby possible moves
        int maxDist = 1;
        Point p = Intersection.gtp2point(topMove.move);
        int minDistanceFromEdge = 1;
        int visits = Integer.parseInt(props.getProperty("paths.visits"));
        for (int x = p.x - maxDist; x <= p.x + maxDist; x++) {
            for (int y = p.y - maxDist; y <= p.y + maxDist; y++) {
                if (x == p.x && y == p.y) continue;
                if (!isOnboard(x, y)) continue;
                if (!node.board.board[x][y].isEmpty()) continue;

                // don't consider moves too close to the edge of the board
                if (x < minDistanceFromEdge || y < minDistanceFromEdge || x >= 19 - minDistanceFromEdge || y >= 19 - minDistanceFromEdge) continue;

                String moveVar = Intersection.toGTPloc(x, y);
                ArrayList<String> analyzeMoves = new ArrayList<>();
                analyzeMoves.add(moveVar);
                KataAnalysisResult karVar = na.analyzeNode(brain, problem, visits, analyzeMoves);

                MoveInfo varMove = karVar.moveInfos.get(0);
                System.out.println("var move: " + varMove.extString());
                double deltaScore = varMove.scoreLead - topMove.scoreLead;
                System.out.println("delta score: " + deltaScore);

                // add to problem paths
                Point varPoint = Intersection.gtp2point(varMove.move);
                problem.addBasicMove(varPoint.x, varPoint.y);
            }
        }
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
        for (int x = p.x - dist; x <= p.x + dist; x++) {
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
}
