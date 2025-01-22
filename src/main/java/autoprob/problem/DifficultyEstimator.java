package autoprob.problem;

import autoprob.KataBrain;
import autoprob.NodeAnalyzer;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;

import java.awt.*;
import java.util.List;
import java.util.Properties;


public class DifficultyEstimator {

    private final Properties props;
    private KataBrain brain;
    private Node problem;
    private KataAnalysisResult rootAnalysis;

    public DifficultyEstimator(Properties props, Node problem, KataBrain brain, KataAnalysisResult rootAnalysis) {
        this.props = props;
        this.problem = problem;
        this.brain = brain;
        this.rootAnalysis = rootAnalysis;
    }
    
    public String estimateStatic() {
        // estimate difficulty by running katago humanSL mode at each human level
        // basically the first level solved is considered the difficulty
        // get correct move for problem
        String correctMove = rootAnalysis.moveInfos.get(0).move;
        StringBuilder sb = new StringBuilder();
        boolean solved = false;
        String diffRank = "";
        for (int level = 20; level >= -8; level -= 1) {
            var na = new NodeAnalyzer(props);
            String rank = (level > 0) ? level + "k" : (-level + 1) + "d";
            KataAnalysisResult kar = null;
            try {
                kar = na.analyzeNode(brain, problem, 1, null, rank);
                // one possibility is to see if the top human move is the correct move
                // the other is to see if the correct move is the top human moves out of the multiple choice
                boolean didSolve = false;
                boolean chooseFromMultiple = true;
                if (chooseFromMultiple) {
                    // see if the correct move is the top human moves out of the multiple choice
                    List<KataAnalysisResult.Policy> top = kar.getTopPolicy(0, kar.humanPolicy); // gets all, sorted
                    // run through these in order. look at the first one that matches one of the paths in the tree
                    humanmoves: for (var pol : top) {
                        String mv = Intersection.toGTPloc(pol.x, pol.y);
                        // run through problem.babies
                        for (var child : problem.babies) {
                            Point p = child.findMove();
                            String childMove = Intersection.toGTPloc(p.x, p.y);
                            if (childMove.equals(mv)) {
                                didSolve = child.searchForTheTruth();
                                System.out.println((didSolve ? "+" : "-") + " found tree move at " + rank + ": " + mv + ", vs correct: " + correctMove + ", child move: " + childMove + ", policy: " + pol.policy);
                                break humanmoves;
                            }
                        }
                    }
                } else {
                    // just look if top human move is correct
                    List<KataAnalysisResult.Policy> top = kar.getTopPolicy(1, kar.humanPolicy);
                    var topMove = top.get(0);
                    String mv = Intersection.toGTPloc(topMove.x, topMove.y);
                    System.out.println("top human move at " + rank + ": " + mv + ", vs correct: " + correctMove);
                    didSolve = mv.equals(correctMove);
                }
                if (didSolve) {
                    if (!solved) {
                        solved = true;
                        diffRank = rank;
                    }
                    sb.append("+");
                } else {
                    sb.append("-");
                    diffRank = rank;
                    solved = false;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        problem.addXtraTag("DIFF", diffRank);
        problem.addXtraTag("HSL", sb.toString());
        return diffRank;
    }

}
