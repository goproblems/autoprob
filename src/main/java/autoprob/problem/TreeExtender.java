package autoprob.problem;

import autoprob.KataBrain;
import autoprob.NodeAnalyzer;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.StoneGroupLogic;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Properties;

public class TreeExtender {
    private final Properties props;
    private final Node solution;
    private final KataBrain brain;
    private final int visits;
    private final StoneGroupLogic sgl = new StoneGroupLogic();
    private final double maxExtendDist;
    protected static final DecimalFormat df = new DecimalFormat("0.00");

    public TreeExtender(Properties props, Node solution, KataBrain brain) {
        this.props = props;
        this.solution = solution;
        this.brain = brain;

        this.visits = Integer.parseInt(props.getProperty("paths.visits"));
        maxExtendDist = Double.parseDouble(props.getProperty("extract.extend_dist", "2.5"));
    }

    // start at correct solution, look for potential responses that are interesting, add them to the tree
    public void extendTree() throws Exception {
        // let's read current position and generate candidate moves (candidates of computer responses to test human)
        // candidates do not need to be the best moves, but may be
        // find them from a combo of top strong move plus human policy
        // the moves need certain characteristics: they are forcing a local response, and not a variety of responses

        System.out.println("================================");
        System.out.println("Extending tree from solution: " + solution.printPath2Here());

        var candidateResponses = generateCandidateResponses();
        System.out.println("Candidate responses: " + candidateResponses);

        evalCandidates(candidateResponses);

        System.out.println("================================");
    }

    private void evalCandidates(ArrayList<String> candidateResponses) throws Exception {
        double minTopMoveScoreMargin = Double.parseDouble(props.getProperty("extend.min_top_move_score_margin", "3"));

        // for each candidate, see if the human responses are good: ie only limited number (one?) and local
        for (var candidate : candidateResponses) {
            Point p = Intersection.gtp2point(candidate);
            Node sol = solution.addBasicMove(p.x, p.y);

            // run katago on candidate
            var na = new NodeAnalyzer(props);
            KataAnalysisResult kar = na.analyzeNode(brain, sol, visits);

            // ensure a) only one response, b) it is local
            double margin = getTopMoveMargin(kar);
            if (margin > minTopMoveScoreMargin && isNearBoard(candidate)) {
                System.out.println("Candidate " + candidate + " is valid: " + df.format(margin));
                // add this response to the tree
                Point responsePoint = Intersection.gtp2point(kar.moveInfos.get(0).move);
                Node response = sol.addBasicMove(responsePoint.x, responsePoint.y);
                // move RIGHT from solution to response
                solution.result = Intersection.INDETERMINATE;
                response.result = Intersection.RIGHT;
                System.out.println("Added response to tree: " + response.printPath2Here());
            } else {
                // remove this candidate from the tree
                System.out.println("Candidate " + candidate + " is invalid: " + df.format(margin));
                solution.babies.remove(sol);
            }
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

    private ArrayList<String> generateCandidateResponses() throws Exception {
        // create list
        var candidates = new ArrayList<String>();

        // run katago on current position
        var na = new NodeAnalyzer(props);
        KataAnalysisResult kar = na.analyzeNode(brain, solution, visits);

        MoveInfo topMove = kar.moveInfos.get(0);
        double dist = sgl.nearestBoardDistance(Intersection.gtp2point(topMove.move), solution.board.board);
        if (dist <= maxExtendDist) {
            candidates.add(topMove.move);
        }

        return candidates;
    }
}
