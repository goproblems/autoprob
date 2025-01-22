package autoprob.problem;

import autoprob.KataBrain;
import autoprob.NodeAnalyzer;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.StoneGroupLogic;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

import java.awt.*;
import java.util.ArrayList;
import java.util.Properties;

public class TreeExtender {
    private final Properties props;
    private final Node solution;
    private final KataBrain brain;
    private final int visits;
    private final StoneGroupLogic sgl = new StoneGroupLogic();
    private final double maxExtendDist;

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
        // for each candidate, see if the human responses are good: ie only limited number (one?) and local
        for (var candidate : candidateResponses) {
            Point p = Intersection.gtp2point(candidate);
            Node n = solution.addBasicMove(p.x, p.y);

            // run katago on candidate
            var na = new NodeAnalyzer(props);
            KataAnalysisResult kar = na.analyzeNode(brain, n, visits);

            // ensure a) only one response, b) it is local

        }

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
