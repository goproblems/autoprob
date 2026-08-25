package autoprob.validate;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Properties;

import autoprob.KataBrain;
import autoprob.go.Board;
import autoprob.NodeAnalyzer;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;

// takes a bare problem position and prepares it so katago can be effective at solving it:
// fills the rest of the board with static fortress stones, then balances komi so that
// the life and death result decides the game
public class ProblemIsolator {
    private static final DecimalFormat df = new DecimalFormat("0.00");
    private final Properties props;
    private final KataBrain brain;

    public static class IsolatedProblem {
        public Node problem; // fresh root with fortress board
        public Board filledStones = new Board(); // artificial stones we placed
        public int solverColor;
        public double komi;
        public KataAnalysisResult rootKar; // analysis of the balanced root position
        public ArrayList<Point> stakes = new ArrayList<>(); // stones whose life depends on solving

        public boolean isFilled(int x, int y) {
            return filledStones.board[x][y].stone != Intersection.EMPTY;
        }
    }

    public ProblemIsolator(Properties props, KataBrain brain) {
        this.props = props;
        this.brain = brain;
    }

    // origRoot: parsed problem root, board holds the problem position, children define to-move
    public IsolatedProblem isolate(Node origRoot) throws Exception {
        IsolatedProblem iso = new IsolatedProblem();
        iso.solverColor = origRoot.getToMove();

        // fresh root with just the problem stones -- the original paths are excised
        Node problem = new Node(null);
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++)
                problem.board.board[x][y].stone = origRoot.board.board[x][y].stone;
        problem.defaultToMoveColor = iso.solverColor;
        problem.addXtraTag("PL", iso.solverColor == Intersection.BLACK ? "B" : "W");
        iso.problem = problem;

        // fill the rest of the board with balanced static stones
        int gap = Integer.parseInt(props.getProperty("redo.gap", "4"));
        new BalancedFortress().build(problem.board, gap, iso.filledStones);

        balanceKomi(iso);
        calcStakes(iso);

        return iso;
    }

    // adjust komi so that with correct play the solver is slightly ahead:
    // solving decides the game, and any slack move loses
    private void balanceKomi(IsolatedProblem iso) throws Exception {
        int visits = Integer.parseInt(props.getProperty("redo.balance_visits", "1500"));
        double margin = Double.parseDouble(props.getProperty("redo.komi_margin", "2.5"));
        NodeAnalyzer na = new NodeAnalyzer(props);

        KataAnalysisResult kar = na.analyzeNode(brain, iso.problem, visits);
        if (kar.isError()) {
            throw new RuntimeException("komi balance analysis failed: " + kar.error);
        }
        double lead = kar.blackScore(); // black perspective
        double targetLead = (iso.solverColor == Intersection.BLACK) ? margin : -margin;
        double oldKomi = 7.5; // fresh problem root has no KM tag yet
        double newKomi = oldKomi + (lead - targetLead);
        newKomi = Math.round(newKomi * 2.0) / 2.0;
        if (Math.abs(newKomi) > 150) {
            System.out.println("WARNING: clamping komi " + newKomi + " to katago limit, the game will not be balanced");
            newKomi = Math.copySign(150, newKomi);
        }
        iso.problem.setXtraTag("KM", String.valueOf(newKomi));
        iso.komi = newKomi;

        System.out.println("komi balance: lead " + df.format(lead) + " at komi " + oldKomi
                + " -> komi " + newKomi + " (solver " + Intersection.color2name(iso.solverColor) + ")");
    }

    // figure out which stones are at stake: compare best play with the solver passing
    private void calcStakes(IsolatedProblem iso) throws Exception {
        int visits = Integer.parseInt(props.getProperty("redo.visits_root", "2000"));
        double threshold = Double.parseDouble(props.getProperty("redo.stake_threshold", "1.3"));
        NodeAnalyzer na = new NodeAnalyzer(props);

        KataAnalysisResult karRoot = na.analyzeNode(brain, iso.problem, visits);
        if (karRoot.isError()) {
            throw new RuntimeException("root analysis failed: " + karRoot.error);
        }
        iso.rootKar = karRoot;

        Node passNode = iso.problem.addBasicMove(19, 19);
        KataAnalysisResult karPass = na.analyzeNode(brain, passNode, visits);
        iso.problem.removeChildNode(passNode);
        if (karPass.isError()) {
            throw new RuntimeException("pass analysis failed: " + karPass.error);
        }

        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (iso.problem.board.board[x][y].stone == Intersection.EMPTY) continue;
                if (iso.isFilled(x, y)) continue;
                double od = karRoot.ownership.get(x + y * 19) - karPass.ownership.get(x + y * 19);
                if (Math.abs(od) > threshold) {
                    iso.stakes.add(new Point(x, y));
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(Intersection.toGTPloc(x, y, 19));
                }
            }
        System.out.println("stakes (" + iso.stakes.size() + "): " + sb);

        if (iso.stakes.isEmpty()) {
            System.out.println("WARNING: no stones seem to be at stake -- position may already be settled");
        }
    }
}
