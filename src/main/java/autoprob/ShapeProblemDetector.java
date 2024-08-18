package autoprob;

import autoprob.go.*;
import autoprob.go.action.*;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class ShapeProblemDetector extends ProblemDetector {
    private static final double MAX_RELEVANCE_DISTANCE = 2.5;
    private KataAnalysisResult rootAnalysis;
    private KataBrain brain;

    // prev is the problem position. kar is the mistake position. node represents prev.
    public ShapeProblemDetector(KataAnalysisResult prev, KataAnalysisResult mistake, Node node, Properties props) throws Exception {
        super(prev, mistake, node, props);
    }

    public void detectProblem(KataBrain brain, boolean forceDetect) throws Exception {
        validProblem = false;
        this.brain = brain;

        if (prev.turnNumber < Integer.parseInt(props.getProperty("shape.min_turn", "10"))) {
            return;
        }

        makeProblem();
        var sgl = new StoneGroupLogic();
        int totalStoneCount = sgl.countStones(problem.board);
        if (totalStoneCount < Integer.parseInt(props.getProperty("shape.min_stones", "10"))) {
            return;
        }

        System.out.println();
        System.out.println("validating shape problem... " + prev.moveInfos.get(0).extString());
        System.out.println("human: " + prev.printTopPolicy(3, prev.humanPolicy));
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
        rootAnalysis = na.analyzeNode(brain, problem, rootVisits);

        MoveInfo topMove = rootAnalysis.moveInfos.get(0);
        System.out.println("top move: " + topMove.extString());

        // check there is only one good top move
        if (!validateTopMoveMargin(rootAnalysis)) {
            if (!forceDetect)
                return;
        }

        var rootGroups = groupAnanlysis(problem.board, rootAnalysis);

        // add solution to problem
        Point p = Intersection.gtp2point(topMove.move);
        Node solution = problem.addBasicMove(p.x, p.y);
        solution.result = Intersection.RIGHT;

        double minBadMovePolicy = Double.parseDouble(props.getProperty("shape.min_bad_move_policy", "0.02"));
        if (!tryMovesWithMinPolicy(topMove, na, rootAnalysis, minBadMovePolicy, rootGroups)) {
            System.out.println("exiting problem, a mistake isn't bad enough");
            if (!forceDetect)
                return;
        }
        if (!tryMovesFromHumanSL(topMove, na, rootAnalysis, minBadMovePolicy, rootGroups)) {
            System.out.println("exiting problem, a humanSL mistake isn't bad enough");
            if (!forceDetect)
                return;
        }

        // if we found no bad moves, then this isn't a problem
        if (problem.babies.size() <= 1) {
            System.out.println("no bad moves found, not a problem");
            if (!forceDetect) {
                return;
            }
        }

        setSolutionComment(solution, rootGroups);

        labelProblemChoices();
        problem.forceMove = true;
        validProblem = true;

        estimateDifficulty();

        System.out.println("END shape problem detect");
    }

    private void estimateDifficulty() {
        // estimate difficulty by running katago humanSL mode at each human level
        // get correct move for problem
        String correctMove = prev.moveInfos.get(0).move;
        StringBuilder sb = new StringBuilder();
        boolean solved = false;
        for (int level = 20; level >= -8; level -= 1) {
            var na = new NodeAnalyzer(props);
            String rank = (level > 0) ? level + "k" : (-level + 1) + "d";
            KataAnalysisResult kar = null;
            try {
                kar = na.analyzeNode(brain, problem, 1, null, rank);
                List<KataAnalysisResult.Policy> top = kar.getTopPolicy(1, kar.humanPolicy);
                var topMove = top.get(0);
                String mv = Intersection.toGTPloc(topMove.x, topMove.y);
                System.out.println("top human move at " + rank + ": " + mv + ", vs correct: " + correctMove);
                if (mv.equals(correctMove)) {
                    if (!solved) {
                        solved = true;
                        problem.addXtraTag("DIFF", rank);
                    }
                    sb.append("+");
                } else {
                    sb.append("-");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        problem.addXtraTag("HSL", sb.toString());
    }

    // set a good readable comment for the solution
    private void setSolutionComment(Node solution, List<StoneGroup> rootGroups) throws Exception {
        // first, let's analyze a contrapositive, play a tenuki in an empty corner and see how that compares in evaluation
        System.out.println("====================================================");
        System.out.println("tenuki analysis on solution");
        // let's find the emptiest corner on the 4-4 point
        Point best = null;
        double farthestDist = 0;
        int inset = 3;
        for (int startx = inset; startx < 19; startx += 18 - inset * 2) {
            for (int starty = inset; starty < 19; starty += 18 - inset * 2) {
                double d = nearestBoardDistance(new Point(startx, starty), problem.board.board);
                if (best == null || d > farthestDist) {
                    best = new Point(startx, starty);
                    farthestDist = d;
                }
            }
        }
        System.out.println("best corner: " + best + ", dist: " + farthestDist);

        // add to tree
        Node tenukiNode = problem.addBasicMove(best.x, best.y);

        var na = new NodeAnalyzer(props);
        int visits = Integer.parseInt(props.getProperty("paths.visits"));
        var karTenuki = na.analyzeNode(brain, tenukiNode, visits);

        MoveInfo topMove = karTenuki.moveInfos.get(0);
        double tenukiResponseDist = nearestBoardDistance(Intersection.gtp2point(topMove.move), tenukiNode.board.board);
        System.out.println("top move after tenuki: " + topMove.extString() + ", distance: " + df.format(tenukiResponseDist));
        double deltaScore = topMove.scoreLead - rootAnalysis.moveInfos.get(0).scoreLead;
        System.out.println("delta score: " + df.format(deltaScore));

        StoneGroupLogic groupLogic = new StoneGroupLogic();
        // we compare initial state to this state
        StoneGroupLogic.PointCount initialPointCount = groupLogic.countCornerPoints(problem.board, rootAnalysis);
        StoneGroupLogic.PointCount pointCount = groupLogic.countCornerPoints(node.board, karTenuki);

        StringBuilder sb = new StringBuilder();
        sb.append("Playing here is worth about ").append(Math.round(Math.abs(deltaScore))).append(" points over playing away in an empty corner. ");
        var tenukiGroups = groupAnanlysis(tenukiNode.board, karTenuki);
        sb.append(groupChanges2CommentSolution(rootGroups, tenukiGroups, tenukiNode, solution));

        String opponentColor = Intersection.color2name(tenukiNode.getToMove() == Intersection.BLACK ? Intersection.BLACK : Intersection.WHITE);
        String opponentColorCaps = Intersection.color2name(tenukiNode.getToMove() == Intersection.BLACK ? Intersection.BLACK : Intersection.WHITE, true);
        boolean sameMove = topMove.move.equals(rootAnalysis.moveInfos.get(0).move);
        sb.append(opponentColorCaps).append(" would have played at ");
        if (sameMove) {
            sb.append("the same place");
        } else {
            Point p = Intersection.gtp2point(topMove.move);
            solution.addAct(new LabelAction("A", p.x, p.y));
            sb.append("A");
//            sb.append(topMove.move);
        }
        sb.append(" if you played away. ");

        if (initialPointCount == null && pointCount == null) {
        }
        else {
            int minPointInterest = 4;
            if (initialPointCount == null) {
                if (pointCount.count >= minPointInterest) {
                    sb.append("Your move prevents ").append(opponentColor).append(" from making about ").append(pointCount.count).append(" points in the corner. ");
                }
            }
        }

        // remove tenuki node since it was only for analysis
        problem.babies.remove(tenukiNode);

        solution.addAct(new CommentAction(sb.toString()));
    }

    // any comment for group changes playing the solution
    private String groupChanges2CommentSolution(List<StoneGroup> rootGroups, List<StoneGroup> tenukiGroups, Node tenukiNode, Node solution) {
        StoneGroupLogic groupLogic = new StoneGroupLogic();

        StringBuilder sb = new StringBuilder();

        double minAbsChange = 0.5;
        double minGroupChange = 2.5; // the whole group ownership status changed by this much

        int markCount = 0; // how many groups we have marked

        for (StoneGroup sg: rootGroups) {
            StoneGroup sg2 = groupLogic.findGroupAfterChange(sg, tenukiNode.board, tenukiGroups);
            if (sg2 == null) {
                // group disappeared
                System.out.println("<==> group disappeared: " + sg);
            }
            else {
                // group changed
                double delta = sg2.ownership - sg.ownership;
                System.out.println("<==> group delta: " + df.format(delta) + ": " + sg);

                if (Math.abs(delta) < minAbsChange) continue;
                int numStones = sg.stones.size();
                // check group change
                if (Math.abs(delta * numStones) < minGroupChange) continue;

                // see if the change is aligned with player color
                boolean playerAligned = problem.getToMove() == Intersection.BLACK ? delta > 0 : delta < 0;

                // mark stones
                String markName = markGroup(sg, solution, markCount++);
                boolean stoneAligned = sg.stone == Intersection.BLACK ? delta > 0 : delta < 0;

                boolean absoluteLife = Math.abs(sg2.ownership) > 0.7;
                System.out.println("=> player aligned: " + playerAligned + ", stone aligned: " + stoneAligned + ", absolute life: " + absoluteLife);
                String stoneAlignText = "";
                if (!stoneAligned) {
                    if (absoluteLife)
                        stoneAlignText = "alive";
                    else
                        stoneAlignText = "stronger";
                } else {
                    if (absoluteLife)
                        stoneAlignText = "dead";
                    else
                        stoneAlignText = "weaker";
                }

                if (playerAligned) {
                } else {
                    sb.append("The stones marked with ").append(markName).append(" are ").append(stoneAlignText).append(". ");
                }
            }
        }
        return sb.toString();
    }

    private List<StoneGroup> groupAnanlysis(Board board, KataAnalysisResult karDeep) {
        StoneGroupLogic groupLogic = new StoneGroupLogic();
        List<StoneGroup> stoneGroups = groupLogic.groupStones(board, karDeep);
        for (StoneGroup g : stoneGroups) {
            System.out.println(g);
        }
        return stoneGroups;
    }

    private boolean validateTopMoveMargin(KataAnalysisResult kar) {
        double minTopMoveScoreMargin = Double.parseDouble(props.getProperty("shape.min_top_move_score_margin", "4"));
        MoveInfo topMove = kar.moveInfos.get(0);
        if (kar.moveInfos.size() > 1) {
            MoveInfo secondMove = kar.moveInfos.get(1);
            System.out.println("second move: " + secondMove.extString());
            double deltaScore = topMove.scoreLead - secondMove.scoreLead;
            System.out.println("delta score: " + df.format(deltaScore));

            // let's make sure this is near the problem though, otherwise not relevant
            Point p = Intersection.gtp2point(secondMove.move);
            double distanceToBoard = nearestBoardDistance(new Point(p.x, p.y), problem.board.board);
            if (distanceToBoard > MAX_RELEVANCE_DISTANCE) {
                System.out.println("second move too far from problem: " + distanceToBoard);
                return true;
            }

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

    // returns false if a move is discoved with too low a delta
    private boolean tryMovesWithMinPolicy(MoveInfo topMove, NodeAnalyzer na, KataAnalysisResult karRoot, double minPolicy, List<StoneGroup> rootGroups) throws Exception {
        // evaluate nearby possible moves
        Point p = Intersection.gtp2point(topMove.move);
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

                double scoreDelta = tryMove(topMove, na, x, y, visits, karRoot, rootGroups);

                // fail if scoreDelta is too low
                double minDelta = Double.parseDouble(props.getProperty("shape.min_mistake_score_delta", "6.0"));
                if (Math.abs(scoreDelta) < minDelta) {
                    System.out.println("score delta too low: " + scoreDelta);
                    return false;
                }
            }
        }
        return true;
    }

    // returns false if a move is discoved with too low a delta
    private boolean tryMovesFromHumanSL(MoveInfo topMove, NodeAnalyzer na, KataAnalysisResult karRoot, double minPolicy, List<StoneGroup> rootGroups) throws Exception {
        Point p = Intersection.gtp2point(topMove.move);
        int visits = Integer.parseInt(props.getProperty("paths.visits"));

        List<KataAnalysisResult.Policy> top = karRoot.getTopPolicy(3, karRoot.humanPolicy);

        for (KataAnalysisResult.Policy pol: top) {
            if (pol.x == p.x && pol.y == p.y) continue; // same as top move
            if (!node.board.board[pol.x][pol.y].isEmpty()) continue;
            // make sure we haven't added this move already
            if (problem.babies.stream().anyMatch(n -> ((Node) n).findMove().equals(new Point(pol.x, pol.y)))) continue;

            if (pol.policy < minPolicy) continue;

            // make sure this move is near other stones
            double distanceToBoard = nearestBoardDistance(new Point(pol.x, pol.y), problem.board.board);
            if (distanceToBoard > 1.7) continue;

            double scoreDelta = tryMove(topMove, na, pol.x, pol.y, visits, karRoot, rootGroups);

            // fail if scoreDelta is too low
            double minDelta = Double.parseDouble(props.getProperty("shape.min_mistake_score_delta", "6.0"));
            if (Math.abs(scoreDelta) < minDelta) {
                System.out.println("humanSL score delta too low: " + scoreDelta);
                return false;
            }
        }
        return true;
    }

//    private void tryNearbyMoves(KataBrain brain, MoveInfo topMove, NodeAnalyzer na, KataAnalysisResult karRoot) throws Exception {
//        // evaluate nearby possible moves
//        int maxDist = 1;
//        Point p = Intersection.gtp2point(topMove.move);
//        int minDistanceFromEdge = 1;
//        int visits = Integer.parseInt(props.getProperty("paths.visits"));
//        for (int x = p.x - maxDist; x <= p.x + maxDist; x++) {
//            for (int y = p.y - maxDist; y <= p.y + maxDist; y++) {
//                if (x == p.x && y == p.y) continue; // same as top move
//                if (!isOnboard(x, y)) continue;
//                if (!node.board.board[x][y].isEmpty()) continue;
//
//                // don't consider moves too close to the edge of the board
//                if (x < minDistanceFromEdge || y < minDistanceFromEdge || x >= 19 - minDistanceFromEdge || y >= 19 - minDistanceFromEdge) continue;
//
//                tryMove(brain, topMove, na, x, y, visits, karRoot, rootGrouping);
//            }
//        }
//    }

    private double tryMove(MoveInfo topMove, NodeAnalyzer na, int x, int y, int visits, KataAnalysisResult karRoot, List<StoneGroup> rootGroups) throws Exception {
        // run katago on this move, forcing it only to consider this option
        String moveVar = Intersection.toGTPloc(x, y);
        ArrayList<String> analyzeMoves = new ArrayList<>();
        analyzeMoves.add(moveVar);
        KataAnalysisResult karMistake = na.analyzeNode(brain, problem, visits, analyzeMoves);

        MoveInfo varMove = karMistake.moveInfos.get(0);
        System.out.println("====================================================");
        System.out.println("var move: " + varMove.extString());
        double deltaScore = varMove.scoreLead - topMove.scoreLead;
        System.out.println("delta score: " + deltaScore);

        // add to problem paths
        Point varPoint = Intersection.gtp2point(varMove.move);
        Node mistake = problem.addBasicMove(varPoint.x, varPoint.y);
        String comment = "This loses " + humanScoreDifference(-deltaScore) + " points. ";
        Node feedbackNode = mistake; // the node where we give user feedback. may change if we add a response

        // check ownership changes for smart comments
        double ownershipThreshold = Double.parseDouble(props.getProperty("shape.ownership_threshold", "0.9"));
        Map<Point, Double> ownershipChanges = calculateOwnershipDelta(karMistake, mistake, karRoot, ownershipThreshold);

        // add the response -- the refutation for a human mistake
        if (varMove.pv.size() > 1) {
            // pv is the move sequence
            String responseMove = varMove.pv.get(1);
            System.out.println("refutation: " + responseMove);
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

        var mistakeGroups = groupAnanlysis(mistake.board, karMistake);

//        comment = ownershipChanges2Comment(ownershipChanges, feedbackNode, comment);
        String groupComment = groupChanges2Comment(rootGroups, mistakeGroups, feedbackNode);
        String cornerPointsComment = cornerPoints2Comment(feedbackNode, karMistake);

        feedbackNode.addAct(new CommentAction(comment + groupComment + cornerPointsComment));

        return deltaScore;
    }

    private String cornerPoints2Comment(Node node, KataAnalysisResult kar) {
        StoneGroupLogic groupLogic = new StoneGroupLogic();
        // we compare initial state to this state
        StoneGroupLogic.PointCount initialPointCount = groupLogic.countCornerPoints(problem.board, rootAnalysis);
        StoneGroupLogic.PointCount pointCount = groupLogic.countCornerPoints(node.board, kar);

        if (initialPointCount == null && pointCount == null) {
            // no corner points at any point
            return "";
        }

        int minPointInterest = 4;

        StringBuilder sb = new StringBuilder();
        if (initialPointCount == null) {
            if (pointCount.count >= minPointInterest) {
                sb.append(Intersection.color2name(pointCount.stone, true)).append(" makes about ").append(pointCount.count).append(" corner points. ");
            }
        }

        return sb.toString();
    }

    // calculate changes between the root groups and the mistake groups
    private String groupChanges2Comment(List<StoneGroup> rootGroups, List<StoneGroup> mistakeGroups, Node feedbackNode) {
        StoneGroupLogic groupLogic = new StoneGroupLogic();

        StringBuilder sb = new StringBuilder();

        double minAbsChange = 0.5;
        double minGroupChange = 2.5; // the whole group ownership status changed by this much

        int markCount = 0; // how many groups we have marked

        for (StoneGroup sg: rootGroups) {
            StoneGroup sg2 = groupLogic.findGroupAfterChange(sg, feedbackNode.board, mistakeGroups);
            if (sg2 == null) {
                // group disappeared
                System.out.println("<==> group disappeared: " + sg);
            }
            else {
                // group changed
                double delta = sg2.ownership - sg.ownership;
                System.out.println("<==> group delta: " + df.format(delta) + ": " + sg);

                if (Math.abs(delta) < minAbsChange) continue;
                int numStones = sg.stones.size();
                // check group change
                if (Math.abs(delta * numStones) < minGroupChange) continue;

                // see if the change is aligned with player color
                boolean playerAligned = problem.getToMove() == Intersection.BLACK ? delta > 0 : delta < 0;

                // mark stones
                String markName = markGroup(sg, feedbackNode, markCount++);
                boolean stoneAligned = sg.stone == Intersection.BLACK ? delta > 0 : delta < 0;
                boolean absoluteLife = Math.abs(sg2.ownership) > 0.7;
                System.out.println("=> player aligned: " + playerAligned + ", stone aligned: " + stoneAligned + ", absolute life: " + absoluteLife);
                String stoneAlignText = "";
                if (stoneAligned) {
                    if (absoluteLife)
                        stoneAlignText = "alive";
                    else
                        stoneAlignText = "stronger";
                } else {
                    if (absoluteLife)
                        stoneAlignText = "dead";
                    else
                        stoneAlignText = "weaker";
                }
                if (playerAligned) {
                    sb.append("The stones marked with ").append(markName).append(" are ").append(stoneAlignText).append(" but this is not sufficient. ");
                } else {
                    sb.append("The stones marked with ").append(markName).append(" are ").append(stoneAlignText).append(". ");
                }
            }
        }
        return sb.toString();
    }

    // mark these stones on the board
    private String markGroup(StoneGroup sg, Node node, int markType) {
        String nm = "overflow";
        for (Point p: sg.stones) {
            switch (markType) {
                case 0:
                    node.addAct(new TriangleAction(p.x, p.y));
                    nm = "a triangle";
                    break;
                case 1:
                    node.addAct(new SquareAction(p.x, p.y));
                    nm = "a square";
                    break;
                case 2:
                    node.addAct(new CircleAction(p.x, p.y));
                    nm = "a circle";
                    break;
            }
        }
        return nm;
    }

    private String ownershipChanges2Comment(Map<Point, Double> ownershipChanges, Node feedbackNode, String comment) {
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
        return comment;
    }

    // return the distance to the nearest stone on the board
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
