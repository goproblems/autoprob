package autoprob;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.action.*;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class ShapeProblemDetector extends ProblemDetector {
    private static final int MAX_RELEVANCE_DISTANCE = 2;

    // prev is the problem position. kar is the mistake position. node represents prev.
    public ShapeProblemDetector(KataAnalysisResult prev, KataAnalysisResult mistake, Node node, Properties props) throws Exception {
        super(prev, mistake, node, props);
    }

    public void detectProblem(KataBrain brain, boolean forceDetect) throws Exception {
        validProblem = false;
        makeProblem();

        System.out.println("validating shape problem...");
        // max policy
        if (calcHighestPrior(prev) > MAX_POLICY) {
            System.out.println("too high policy: " + calcHighestPrior(prev));
            if (!forceDetect) return;
        }

        // we basically ignore the mistake part, just go off node/prev

        // check there is only one good top move
        if (!validateTopMoveMargin(prev)) {
            if (!forceDetect)
                return;
        }

        // run katago with this new problem, make sure we really want to play here still
        boolean dbgOwn = Boolean.parseBoolean(props.getProperty("search.debug_pass_ownership", "false"));
        int rootVisits = Integer.parseInt(props.getProperty("search.root_visits"));
        var na = new NodeAnalyzer(props, dbgOwn);
        KataAnalysisResult karDeep = na.analyzeNode(brain, problem, rootVisits);

        MoveInfo topMove = karDeep.moveInfos.get(0);
        System.out.println("top move: " + topMove.extString());

        // check there is only one good top move
        if (!validateTopMoveMargin(karDeep)) {
            if (!forceDetect)
                return;
        }

        // add solution to problem
        Point p = Intersection.gtp2point(topMove.move);
        Node solution = problem.addBasicMove(p.x, p.y);
        solution.result = Intersection.RIGHT;

        double minBadMovePolicy = Double.parseDouble(props.getProperty("shape.min_bad_move_policy", "0.02"));
        tryMovesWithPolicy(brain, topMove, na, karDeep, minBadMovePolicy);

//        tryNearbyMoves(brain, topMove, na, karDeep);

        // if we found no bad moves, then this isn't a problem
        if (problem.babies.size() <= 1) {
            System.out.println("no bad moves found, not a problem");
            if (!forceDetect) {
                return;
            }
        }

        labelProblemChoices();
        problem.forceMove = true;
        validProblem = true;
    }

    private boolean validateTopMoveMargin(KataAnalysisResult kar) {
        double minTopMoveScoreMargin = Double.parseDouble(props.getProperty("shape.min_top_move_score_margin", "4"));
        MoveInfo topMove = kar.moveInfos.get(0);
        if (kar.moveInfos.size() > 1) {
            MoveInfo secondMove = kar.moveInfos.get(1);
            System.out.println("second move: " + secondMove.extString());
            double deltaScore = topMove.scoreLead - secondMove.scoreLead;
            System.out.println("delta score: " + deltaScore);
            if (deltaScore < minTopMoveScoreMargin) {
                System.out.println("not a good shape problem, second move too good");
                return false;
            }
        }
        return true;
    }

    protected double calcHighestPrior(KataAnalysisResult kar) {
        double highestPrior = 0;
        for (MoveInfo mi: kar.moveInfos) {
            highestPrior = Math.max(highestPrior, mi.prior);
        }
        return highestPrior;
    }

    // put a, b, c etc on the board to label the choices
    private void labelProblemChoices() {
        int i = 0;
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                // enumerate children
                var cc = problem.babies;
                for (var c : cc) {
                    Node child = (Node) c;
                    Point p = child.findMove();
                    if (p.x == x && p.y == y) {
                        char ch = (char) ('a' + i);
                        problem.board.board[x][y].text = Character.toString(ch);
                        problem.addAct(new LabelAction(Character.toString(ch), x, y));
                        i++;
                    }
                }
            }
    }

    private void tryMovesWithPolicy(KataBrain brain, MoveInfo topMove, NodeAnalyzer na, KataAnalysisResult karRoot, double minPolicy) throws Exception {
        // evaluate nearby possible moves
        int maxDist = 1;
        Point p = Intersection.gtp2point(topMove.move);
        int minDistanceFromEdge = 1;
        int visits = Integer.parseInt(props.getProperty("paths.visits"));
        for (int x = 0; x < 19; x++) {
            for (int y = 0; y < 19; y++) {
                if (x == p.x && y == p.y) continue; // same as top move
                if (!node.board.board[x][y].isEmpty()) continue;

                double policy = karRoot.policy.get(x + y * 19);
                if (policy < minPolicy) continue;

                // make sure this move is near other stones
                double distanceToBoard = nearestBoardDistance(new Point(x, y), problem.board.board);
                if (distanceToBoard > 1.7) continue;

                tryMove(brain, topMove, na, x, y, visits, karRoot);
            }
        }
    }

    private void tryNearbyMoves(KataBrain brain, MoveInfo topMove, NodeAnalyzer na, KataAnalysisResult karRoot) throws Exception {
        // evaluate nearby possible moves
        int maxDist = 1;
        Point p = Intersection.gtp2point(topMove.move);
        int minDistanceFromEdge = 1;
        int visits = Integer.parseInt(props.getProperty("paths.visits"));
        for (int x = p.x - maxDist; x <= p.x + maxDist; x++) {
            for (int y = p.y - maxDist; y <= p.y + maxDist; y++) {
                if (x == p.x && y == p.y) continue; // same as top move
                if (!isOnboard(x, y)) continue;
                if (!node.board.board[x][y].isEmpty()) continue;

                // don't consider moves too close to the edge of the board
                if (x < minDistanceFromEdge || y < minDistanceFromEdge || x >= 19 - minDistanceFromEdge || y >= 19 - minDistanceFromEdge) continue;

                tryMove(brain, topMove, na, x, y, visits, karRoot);
            }
        }
    }

    private void tryMove(KataBrain brain, MoveInfo topMove, NodeAnalyzer na, int x, int y, int visits, KataAnalysisResult karRoot) throws Exception {
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
        Node mistake = problem.addBasicMove(varPoint.x, varPoint.y);
        String comment = "This loses " + humanScoreDifference(-deltaScore) + " points.";
        Node feedbackNode = mistake; // the node where we give user feedback. may change if we add a response

        // check ownership changes for smart comments
        double ownershipThreshold = Double.parseDouble(props.getProperty("shape.ownership_threshold", "0.9"));
        Map<Point, Double> ownershipChanges = calculateOwnershipDelta(karVar, mistake, karRoot, ownershipThreshold);

        // add the response
        if (varMove.pv.size() > 1) {
            String responseMove = varMove.pv.get(1);
            System.out.println("refutation response: " + responseMove);
            Point responsePoint = Intersection.gtp2point(responseMove);
            double distanceToBoard = nearestBoardDistance(responsePoint, mistake.board.board);
            if (distanceToBoard > MAX_RELEVANCE_DISTANCE) {
                // just end variation here, since the response is a tenuki
                System.out.println("refutation response too far from board: " + distanceToBoard);
            } else {
                Node refutationNode = mistake.addBasicMove(responsePoint.x, responsePoint.y);
                feedbackNode = refutationNode;
            }
        }

        if (!ownershipChanges.isEmpty()) {
            int hasDeath = 0;
            int hasLife = 0;
            // mark stones
            for (Point p : ownershipChanges.keySet()) {
                // ignore empty points. needed a stone at start and at end
                if (problem.board.board[p.x][p.y].isEmpty()) continue;
                if (feedbackNode.board.board[p.x][p.y].isEmpty()) continue;
                double lifeChange = ownershipChanges.get(p);
                if (lifeChange > 0) {
                    feedbackNode.addAct(new SquareAction(p.x, p.y));
                    hasLife++;
                }
                else {
                    feedbackNode.addAct(new CircleAction(p.x, p.y));
                    hasDeath++;
                }
            }
            if (hasLife > 0) {
                comment += " The square marked " + (hasLife == 1 ? "stone is" : "stones are") + " more alive.";
            }
            if (hasDeath > 0) {
                comment += " The circle marked " + (hasDeath == 1 ? "stone is" : "stones are") + " more dead.";
            }
        }

        feedbackNode.addAct(new CommentAction(comment));
    }

    private double nearestBoardDistance(Point p, Intersection[][] board) {
        double minDist = 100;
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (board[x][y].isEmpty()) continue;
                double dist = Math.sqrt((x - p.x) * (x - p.x) + (y - p.y) * (y - p.y));
                minDist = Math.min(minDist, dist);
            }
        return minDist;
    }

    private String humanScoreDifference(double v) {
        return "about " + Math.round(v);
    }

    protected void makeProblemStones() {
        // copy all stones from node board
        var b = problem.board.board;
        var src = node.board.board;

        // expand flood outwards from move
        Node child = (Node) node.babies.get(0);
        Point lastMove = child.findMove();

        sparseFlood(lastMove, b, src, 2);

        // if the previous move stone got placed, let's mark it by default
        Point prevGameMove = node.findMove();
        if (prevGameMove != null && prevGameMove.x != 19) {
            if (problem.board.board[prevGameMove.x][prevGameMove.y].stone != Intersection.EMPTY) {
                problem.addAct(new TriangleAction(prevGameMove.x, prevGameMove.y));
            }
        }
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

    public Map<Point, Double> calculateOwnershipDelta(KataAnalysisResult kar, Node node, KataAnalysisResult prev, double threshold) {
        double maxDelta = 0;
        var ownershipChanges = new HashMap<Point, Double>();

        boolean dbg = Boolean.parseBoolean(props.getProperty("extract.debug_print_ownership", "false"));

        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                int stn = node.board.board[x][y].stone;
                if (stn == 0) continue;
                double od = kar.ownership.get(x + y * 19) - prev.ownership.get(x + y * 19);
                // negative ownership is white, positive is black
                maxDelta = Math.max(maxDelta, Math.abs(od));
                if (Math.abs(od) > threshold) {
                    if (dbg) {
                        System.out.println("ownership delta: " + df.format(od) + ", " + Intersection.toGTPloc(x, y, 19) +
                                " (" + df.format(prev.ownership.get(x + y * 19)) + " -> " + df.format(kar.ownership.get(x + y * 19)) + ")");
                    }
                    double lifeChange = od;
                    if (stn == Intersection.WHITE) {
                        lifeChange = -lifeChange;
                    }
                    ownershipChanges.put(new Point(x, y), lifeChange);
                }
            }
        if (dbg) {
            System.out.println("max ownership delta (stones relatively changing sides): " + df.format(maxDelta));
        }
        return ownershipChanges;
    }
}
