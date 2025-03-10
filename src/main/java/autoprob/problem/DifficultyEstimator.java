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

    public double rank2elo(String rank) {
        String numString = rank.substring(0, rank.length() - 1);
        int num = Integer.parseInt(numString);
        if (rank.endsWith("d")) {
            return 3000 + 100 * (num - 1);
        } else {
            return 3000 - 100 * num;
        }
    }

    // calculates elo given a win probability and a strength rating
    public double calculateEloX(double e, double p)
    {
        return e + 400 * Math.log10((1 - p) / p);
    }

    public String elo2rank(double elo) {
        if (elo >= 3000) {
            int dan = ((int)((elo - 3000) / 100) + 1);
            dan = Math.min(Math.max(dan, 1), 9);  // Ensure dan between 1 and 9
            return dan + "d";
        } else {
            int kyu = ((int)((2999 - elo) / 100) + 1);
            kyu = Math.min(Math.max(kyu, 1), 30); // Ensure kyu between 1 and 30
            return kyu + "k";
        }
    }

    // do a reverse elo calculation given solve percentages on the root node, no exploration
    public String estimateProbabilityFromRoot(boolean doRecurse) throws Exception {
        System.out.println("------------- starting estimateProbabilityFromRoot recurse: " + doRecurse);

        String onlyOneRank = props.getProperty("estimator.one_rank", "");

        // create a map of ranks to string descriptions for later
        var rankMap = new java.util.HashMap<String, String>();

        // loop through all human values
        int cnt = 0;
        double sum = 0;
        for (int level = 20; level >= -8; level -= 1) {
            String rank = (level > 0) ? level + "k" : (-level + 1) + "d";

            if (!onlyOneRank.isEmpty() && !rank.equals(onlyOneRank)) {
                continue;
            }
            System.out.println("rank: " + rank);

            double p = getEstProbability(rank, problem, doRecurse);

            double rankElo = rank2elo(rank);
            double elo = calculateEloX(rankElo, p);
            String estRank = elo2rank(elo);
            String desc = "winningOdds: " + p + ", rankElo: " + (int)rankElo + ", elo: " + (int)elo + ", estRank: " + estRank;
            System.out.println(desc);
            rankMap.put(rank, estRank);
            sum += elo;
            cnt++;
        }

        double avgElo = sum / cnt;
        String estRank = elo2rank(avgElo);
        System.out.println("avgElo: " + avgElo + ", estRank: " + estRank);

        problem.addXtraTag("DIFF", estRank);

        // print out rankmap in rank order
        for (int level = 20; level >= -8; level -= 1) {
            String rank = (level > 0) ? level + "k" : (-level + 1) + "d";
            if (rankMap.containsKey(rank))
                System.out.println(rank + ": " + rankMap.get(rank));
        }

        return estRank;
    }

    private double getEstProbability(String rank, Node node, boolean doRecurse) throws Exception {
        KataAnalysisResult kar;
        NodeAnalyzer na = new NodeAnalyzer(props);
        // run human eval
        kar = na.analyzeNode(brain, node, 1, null, rank);

        // estimate probability this human level chooses a right move
        double winningOdds = calcPercentageCorrect(rank, kar.humanPolicy, node, doRecurse);
        System.out.println("winningOdds: " + winningOdds);
        return winningOdds;
    }

    // looks at policy chances vs tree to see how likely the policy is correct
    private double calcPercentageCorrect(String rank, List<Double> policy, Node n, boolean doRecurse) throws Exception {
        double rightTotal = 0, wrongTotal = 0;
        double totalMoveChoice = 0; // total probability of all moves we might pick. this can be less than 1 if situations are forced
        boolean isForced = n.forceMove;
        boolean assumeForcee = Boolean.parseBoolean(props.getProperty("estimator.assume_forced", "false"));

        if (isForced) {
            System.out.println("Forced move");
        }
        for (int y = 0; y < 19; y++) {
            for (int x = 0; x < 19; x++) {
                double p = policy.get(x + y * 19);
                if (p < 0) continue; // ignore illegal moves
                // is this a move, and is it right?
                Node child = n.getMoveChild(x, y);
                if (isForced && child == null) {
                    // ignore since they must pick from tree
                    continue;
                }
                totalMoveChoice += p; // this might be chosen
                if (child != null && child.searchForTheTruth()) {
                    if (doRecurse) {
                        // recurse
                        double recursePct = calcRecursePercentage(rank, child);
                        double newp = recursePct * p; // weight by how likely this move is to be right
                        System.out.println("recursePct: " + recursePct + ", newp: " + newp + ", oldp: " + p);
                        p = newp;
                    }
                    rightTotal += p;
                } else {
                    wrongTotal += p;
                }
            }
        }
        System.out.println("rightTotal: " + rightTotal + ", wrongTotal: " + wrongTotal + ", totalMoveChoice: " + totalMoveChoice);
        // normalize and return
        if (rightTotal  == 0) {
            rightTotal = 0.0001; // at least a misclick, surely
        }
        return rightTotal / (totalMoveChoice);
    }

    // we go deeper, first do computer response options then combine
    private double calcRecursePercentage(String rank, Node n) throws Exception {
        // n represents the last human move
        if (n.babies.isEmpty()) {
            // no computer response, just return 1
            return 1;
        }

        // normally we just look at the first branch, but if there's a CHOICE tag, we look at all
        // for now just keep it simple

        Node computerMove = n.babies.get(0);
        Point move = computerMove.getMoveAction().getMove();
        System.out.println("computerMove: " + Intersection.toGTPloc(move.x, move.y));

        double p = getEstProbability(rank, computerMove, true);
        System.out.println("response to : " + Intersection.toGTPloc(move.x, move.y) + " right probability: " + p);
        return p;
    }
}
