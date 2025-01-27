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
    protected static final DecimalFormat df = new DecimalFormat("0.00");

    public TreeExtender(Properties props, Node solution, KataBrain brain) {
        this.props = props;
        this.solution = solution;
        this.brain = brain;

        this.visits = Integer.parseInt(props.getProperty("paths.visits"));
        maxExtendDist = Double.parseDouble(props.getProperty("extract.extend_dist", "2.5"));
    }

    // start at correct solution (1 move from start of problem), look for potential responses that are interesting, add them to the tree
    public void extendTree() throws Exception {
        // let's read current position and generate candidate moves (candidates of computer responses to test human)
        // candidates do not need to be the best moves, but may be
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
        var na = new NodeAnalyzer(props);
        kar = na.analyzeNode(brain, solution, visits);
    }

    private void evalCandidates(ArrayList<String> candidateResponses) throws Exception {
        double minTopMoveScoreMargin = Double.parseDouble(props.getProperty("extend.min_top_move_score_margin", "3"));

        // for each candidate, see if the human responses are good: ie only limited number (one?) and local
        // add them to the tree if good
        for (var candidate : candidateResponses) {
            Point p = Intersection.gtp2point(candidate);
            Node solTest = solution.addBasicMove(p.x, p.y);

            // run katago on candidate
            var na = new NodeAnalyzer(props);
            KataAnalysisResult karTest = na.analyzeNode(brain, solTest, visits);

            // ensure a) only one response, b) it is local
            double margin = getTopMoveMargin(karTest);
            if (margin > minTopMoveScoreMargin && isNearBoard(candidate)) {
                System.out.println("Candidate " + candidate + " is valid: " + df.format(margin));
                // add this response to the tree
                Point responsePoint = Intersection.gtp2point(karTest.moveInfos.get(0).move);
                Node response = solTest.addBasicMove(responsePoint.x, responsePoint.y);
                // move RIGHT from solution to response
                solution.result = Intersection.INDETERMINATE;
                response.result = Intersection.RIGHT;
                System.out.println("Added response to tree: " + response.printPath2Here());

                addResponseComments(response, karTest, candidate);
            } else {
                // remove this candidate from the tree
                System.out.println("Candidate " + candidate + " is invalid: " + df.format(margin));
                solution.babies.remove(solTest);
            }
        }

        markChoice(solution);
    }

    private void markChoice(Node solution) {
        if (solution.babies.isEmpty()) {
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
        List<KataAnalysisResult.Policy> top20k = getHumanPolicy("20k", solution, 5);
        for (KataAnalysisResult.Policy p : top20k) {
            String move = Intersection.toGTPloc(p.x, p.y);
            System.out.println("Move " + move + " human policy: " + df.format(p.policy));
            if (p.policy < minHumanPolicy) {
                continue;
            }
            if (!candidates.contains(move)) {
                candidates.add(move);
            }
        }

        return candidates;
    }

    private List<KataAnalysisResult.Policy> getHumanPolicy(String rank, Node node, int num) throws Exception {
        KataAnalysisResult kar = null;
        var na = new NodeAnalyzer(props);
        kar = na.analyzeNode(brain, node, 1, null, rank);
        // see if the correct move is the top human moves out of the multiple choice
        return kar.getTopPolicy(num, kar.humanPolicy);
    }
}
