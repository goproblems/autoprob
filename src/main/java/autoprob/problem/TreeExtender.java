package autoprob.problem;

import autoprob.KataBrain;
import autoprob.NodeAnalyzer;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.StoneGroupLogic;
import autoprob.go.action.CommentAction;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class TreeExtender {
    private final Properties props;
    private final Node solution;
    private final KataBrain brain;
    private final int visits;
    private final StoneGroupLogic sgl = new StoneGroupLogic();
    private final double maxExtendDist;
    private KataAnalysisResult kar;
    private NodeAnalyzer na;
    protected static final DecimalFormat df = new DecimalFormat("0.00");

    public TreeExtender(Properties props, Node solution, KataBrain brain) {
        this.props = props;
        this.solution = solution;
        this.brain = brain;

        this.visits = Integer.parseInt(props.getProperty("paths.visits"));
        maxExtendDist = Double.parseDouble(props.getProperty("extract.extend_dist", "2.5"));

        na = new NodeAnalyzer(props);
    }

    // start at correct solution (1 move from start of problem), look for potential responses that are interesting, add them to the tree
    public void extendTree() throws Exception {
        // let's read current position and generate candidate moves (candidates of computer responses to test human)
        // candidates do not need to be the best moves, but they may be
        // find them from a combo of top strong move plus human policy
        // the moves need certain characteristics: they are forcing a local response, and not a variety of responses

        System.out.println("================================");
        System.out.println("Extending tree from solution: " + solution.printPath2Here());

        analyzeStartingPosition();

        var candidateResponses = generateCandidateResponses();
        System.out.println("Candidate responses: " + candidateResponses);

        evalCandidates(candidateResponses);

        System.out.println("================================");
    }

    private void analyzeStartingPosition() throws Exception {
        // run katago on current position
        kar = na.analyzeNode(brain, solution, visits);
    }

    private void evalCandidates(ArrayList<String> candidateResponses) throws Exception {
        double minTopMoveScoreMargin = Double.parseDouble(props.getProperty("extend.min_top_move_score_margin", "3"));

        // for each candidate, see if the human responses are good: ie only limited number (one?) and local
        // add them to the tree if good
        for (var candidate : candidateResponses) {
            System.out.println("==> Evaluating candidate " + candidate);
            Point p = Intersection.gtp2point(candidate);
            Node solTest = solution.addBasicMove(p.x, p.y);

            // run katago on candidate
            KataAnalysisResult karTest = na.analyzeNode(brain, solTest, visits);

            // ensure a) only one response, b) it is local
            double margin = getTopMoveMargin(karTest);
            if (margin > minTopMoveScoreMargin && isNearBoard(candidate)) {
                String responseMove = karTest.moveInfos.get(0).move;
                System.out.println("Candidate " + candidate + " is valid: " + df.format(margin));
                // add this response to the tree
                Point responsePoint = Intersection.gtp2point(responseMove);
                Node response = solTest.addBasicMove(responsePoint.x, responsePoint.y);
                // move RIGHT from solution to response
                solution.result = Intersection.INDETERMINATE;
                response.result = Intersection.RIGHT;
                System.out.println("Added response to tree: " + response.printPath2Here());

                addResponseComments(response, karTest, candidate);

                // also add mistakes and their refutations
                addCandidateResponseMistakes(karTest, solTest, responseMove);
            } else {
                // remove this candidate from the tree
                System.out.println("Candidate " + candidate + " is invalid: " + df.format(margin));
                solution.babies.remove(solTest);
            }
        }

        markChoice(solution);
    }

    // a computer has added a testing move. we want to add possible human mistakes and their refutations
    private void addCandidateResponseMistakes(KataAnalysisResult karTest, Node solTest, String correctMove) throws Exception {
        System.out.println("==> adding response mistakes, not correct move at " + correctMove);
        ArrayList<String> humanOptions = generateHumanOptions(karTest, solTest);

        for (var humanMistake : humanOptions) {
            // skip if correct move
            if (humanMistake.equals(correctMove)) {
                continue;
            }
            Point p = Intersection.gtp2point(humanMistake);
            Node mistake = solTest.addBasicMove(p.x, p.y);
            System.out.println("Added mistake to tree: " + mistake.printPath2Here());

            // evaluate refutation
            var mistakeKar = na.analyzeNode(brain, mistake, visits);
            String refutationMove = mistakeKar.moveInfos.get(0).move;
            Point refutationPoint = Intersection.gtp2point(refutationMove);

            // if this is far from the problem, we don't add it, just terminate the branch here
            double dist = sgl.nearestBoardDistance(refutationPoint, mistake.board.board);
            if (dist > maxExtendDist) {
                System.out.println("Refutation " + refutationMove + " too far from board: " + df.format(dist));
                String clr = Intersection.color2name(mistake.getToMove() == Intersection.BLACK ? Intersection.BLACK : Intersection.WHITE, true);
                String comment = "This loses approximately " + Math.round(Math.abs(karTest.blackScore() - mistakeKar.blackScore())) + " points. " + clr + " will play away next.";
                mistake.addAct(new CommentAction(comment));
            } else {
                Node refutationNode = mistake.addBasicMove(refutationPoint.x, refutationPoint.y);

                // add comment for how much this mistake cost
                String comment = "Your mistake at " + humanMistake + " lost approximately " + Math.round(Math.abs(karTest.blackScore() - mistakeKar.blackScore())) + " points";
                refutationNode.addAct(new CommentAction(comment));
            }
        }
    }

    // possible computer responses, some of which may be losing points
    private ArrayList<String> generateHumanOptions(KataAnalysisResult nodeKar, Node node) throws Exception {
        // create list
        var candidates = new ArrayList<String>();

        // add human policy moves
        double minHumanPolicy = Double.parseDouble(props.getProperty("extend.min_human_mistake_policy", "0.05"));
        List<KataAnalysisResult.Policy> topHuman = getHumanPolicy("5k", node, 5);
        for (KataAnalysisResult.Policy p : topHuman) {
            String move = Intersection.toGTPloc(p.x, p.y);
            double dist = sgl.nearestBoardDistance(new Point(p.x, p.y), node.board.board);
            if (dist > maxExtendDist) {
                System.out.println("Move " + move + " too far from board: " + df.format(dist));
                continue;
            }
            System.out.println("human option " + move + " human policy: " + df.format(p.policy) + (p.policy < minHumanPolicy ? "" : " x"));
            if (p.policy < minHumanPolicy) {
                continue;
            }
            if (!candidates.contains(move)) {
                candidates.add(move);
            }
        }

        return candidates;
    }

    private void markChoice(Node solution) {
        if (solution.babies.size() < 2) {
            return; // no choices
        }

        // mark all babies
        for (var baby : solution.babies) {
            baby.isChoice = true;
        }
    }

    private void addResponseComments(Node response, KataAnalysisResult karTest, String testMove) {
        StringBuilder sb = new StringBuilder();

        // if the previous test move dropped enough points, point this out
        double minPointDropNote = Double.parseDouble(props.getProperty("extend.min_point_drop_note", "2"));

        double drop = Math.abs(karTest.blackScore() - kar.blackScore());
        if (drop > minPointDropNote) {
            String clr = Intersection.color2name(response.getToMove() == Intersection.BLACK ? Intersection.BLACK : Intersection.WHITE, true);
            sb.append("Note: the ").append(clr).append(" move at ").append(testMove).append(" lost about ").append(Math.round(drop)).append(" points");
        }

        if (!sb.isEmpty()) {
            response.addAct(new CommentAction(sb.toString()));
        }
    }

    private double getTopMoveMargin(KataAnalysisResult kar) {
        MoveInfo topMove = kar.moveInfos.get(0);
        if (kar.moveInfos.size() > 1) {
            MoveInfo secondMove = kar.moveInfos.get(1);
            System.out.println("first move: " + topMove.extString());
            System.out.println("second move: " + secondMove.extString());
            double deltaScore = Math.abs(topMove.scoreLead - secondMove.scoreLead); // must abs because could be for B or W
            System.out.println("delta score: " + df.format(deltaScore));
            return deltaScore;
        }
        return 1000;
    }

    private boolean isNearBoard(String move) {
        Point p = Intersection.gtp2point(move);
        double dist = sgl.nearestBoardDistance(p, solution.board.board);
        return dist <= maxExtendDist;
    }

    // possible computer responses, some of which may be losing points
    private ArrayList<String> generateCandidateResponses() throws Exception {
        // create list
        var candidates = new ArrayList<String>();

        MoveInfo topMove = kar.moveInfos.get(0);
        double dist = sgl.nearestBoardDistance(Intersection.gtp2point(topMove.move), solution.board.board);
        if (dist <= maxExtendDist) {
            candidates.add(topMove.move);
        }

        // add human policy moves
        double minHumanPolicy = Double.parseDouble(props.getProperty("extend.min_human_policy", "0.10"));
        List<String> ranks = List.of("20k", "10k", "1k", "4d");
        List<KataAnalysisResult.Policy> topPolicy = getMergedPolicy(ranks, solution, 5);
        for (KataAnalysisResult.Policy p : topPolicy) {
            String move = Intersection.toGTPloc(p.x, p.y);
            System.out.println("Move " + move + " human policy: " + df.format(p.policy) + (p.policy < minHumanPolicy ? "" : " x"));
            dist = sgl.nearestBoardDistance(Intersection.gtp2point(move), solution.board.board);
            if (dist > maxExtendDist) {
                System.out.println("Move " + move + " too far from top move: " + df.format(dist));
                continue;
            }
            if (p.policy < minHumanPolicy) {
                continue;
            }
            if (!candidates.contains(move)) {
                candidates.add(move);
            }
        }

        return candidates;
    }

    private List<KataAnalysisResult.Policy> getMergedPolicy(List<String> ranks, Node node, int num) throws Exception {
        List<KataAnalysisResult.Policy> merged = new ArrayList<>();
        for (String rank : ranks) {
            var l = getHumanPolicy(rank, node, num);
            // merge policies
            for (var p : l) {
                if (!merged.contains(p)) {
                    merged.add(p);
                }
            }
        }
        return merged;
    }

    private List<KataAnalysisResult.Policy> getHumanPolicy(String rank, Node node, int num) throws Exception {
        KataAnalysisResult kar = null;
        kar = na.analyzeNode(brain, node, 1, null, rank);
        // see if the correct move is the top human moves out of the multiple choice
        return kar.getTopPolicy(num, kar.humanPolicy);
    }
}
