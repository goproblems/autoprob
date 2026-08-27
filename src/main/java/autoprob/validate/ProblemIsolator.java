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
        public KataAnalysisResult solvedKar; // analysis of the solved state (after the best first move)
        public double targetLead; // black score the komi balance aims for with correct play
        public ArrayList<Point> stakes = new ArrayList<>(); // stones whose life depends on solving
        public ArrayList<String> warnings = new ArrayList<>();

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

    // after a generation run discovered value the isolation baseline missed (score
    // drift), rebuild the stakes and komi from the solved end position of that run.
    // endNode holds the board after the generated line was played out.
    // returns true if the baseline actually moved: a mid-line drift can be a
    // transient capture-phase dip, in which case regenerating is a waste.
    public boolean recalibrate(IsolatedProblem iso, Node endNode) throws Exception {
        int visits = Integer.parseInt(props.getProperty("redo.visits_root", "2000"));
        double threshold = Double.parseDouble(props.getProperty("redo.stake_threshold", "1.3"));
        NodeAnalyzer na = new NodeAnalyzer(props);

        KataAnalysisResult karEnd = na.analyzeNode(brain, endNode, visits);
        if (karEnd.isError()) {
            throw new RuntimeException("recalibration end analysis failed: " + karEnd.error);
        }
        Node passNode = iso.problem.addBasicMove(19, 19);
        KataAnalysisResult karPass = na.analyzeNode(brain, passNode, visits);
        iso.problem.removeChildNode(passNode);
        if (karPass.isError()) {
            throw new RuntimeException("recalibration pass analysis failed: " + karPass.error);
        }

        iso.solvedKar = karEnd;
        iso.stakes.clear();
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (iso.problem.board.board[x][y].stone == Intersection.EMPTY) continue;
                if (iso.isFilled(x, y)) continue;
                double od = karEnd.ownership.get(x + y * 19) - karPass.ownership.get(x + y * 19);
                if (Math.abs(od) > threshold) {
                    iso.stakes.add(new Point(x, y));
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(Intersection.toGTPloc(x, y, 19));
                }
            }
        double drift = karEnd.blackScore() - iso.targetLead;
        setKomi(iso, iso.komi + drift);
        System.out.println("recalibrated: komi " + iso.komi + ", stakes (" + iso.stakes.size() + "): " + sb
                + ", end drift " + df.format(drift));
        return Math.abs(drift) > 5;
    }

    // adjust komi so that with correct play the solver is slightly ahead:
    // solving decides the game, and any slack move loses
    private void balanceKomi(IsolatedProblem iso) throws Exception {
        int visits = Integer.parseInt(props.getProperty("redo.balance_visits", "1500"));
        double margin = Double.parseDouble(props.getProperty("redo.komi_margin", "2.5"));
        iso.targetLead = (iso.solverColor == Intersection.BLACK) ? margin : -margin;
        NodeAnalyzer na = new NodeAnalyzer(props);

        KataAnalysisResult kar = na.analyzeNode(brain, iso.problem, visits);
        if (kar.isError()) {
            throw new RuntimeException("komi balance analysis failed: " + kar.error);
        }
        double lead = kar.blackScore(); // black perspective
        double oldKomi = 7.5; // fresh problem root has no KM tag yet
        setKomi(iso, oldKomi + (lead - iso.targetLead));
        System.out.println("komi balance: lead " + df.format(lead) + " at komi " + oldKomi
                + " -> komi " + iso.komi + " (solver " + Intersection.color2name(iso.solverColor) + ")");
    }

    private void setKomi(IsolatedProblem iso, double komi) {
        komi = Math.round(komi * 2.0) / 2.0;
        if (Math.abs(komi) > 150) {
            System.out.println("WARNING: clamping komi " + komi + " to katago limit, the game will not be balanced");
            komi = Math.copySign(150, komi);
        }
        iso.problem.setXtraTag("KM", String.valueOf(komi));
        iso.komi = komi;
    }

    // figure out which stones are at stake: compare the solved state with the solver
    // passing. the solved side is anchored on the position after the best first move,
    // where the search is concentrated -- a diffuse root analysis can miss a hard
    // tesuji entirely and make the whole group look unconditionally settled.
    // retries with more visits when the result looks inconsistent.
    private void calcStakes(IsolatedProblem iso) throws Exception {
        int visits = Integer.parseInt(props.getProperty("redo.visits_root", "2000"));
        int minStones = Integer.parseInt(props.getProperty("redo.threat_stones", "4"));
        double scoreSwing = calcStakesPass(iso, visits);
        if (iso.stakes.isEmpty() || (iso.stakes.size() < minStones && Math.abs(scoreSwing) > 10)) {
            System.out.println("stakes look inconsistent (" + iso.stakes.size() + " stones, swing "
                    + df.format(scoreSwing) + "), retrying with more visits");
            calcStakesPass(iso, visits * 2);
            if (!iso.stakes.isEmpty()) {
                iso.warnings.add("stakes only settled on deeper retry, consider higher redo.visits_root");
            }
        }
        if (iso.stakes.isEmpty()) {
            String warning = "no stones seem to be at stake -- position may already be settled";
            System.out.println("WARNING: " + warning);
            iso.warnings.add(warning);
        }

        // the first komi balance ran on the root analysis; if the solved state scores
        // differently (root search missed the solution), rebalance on the solved state
        double drift = iso.solvedKar.blackScore() - iso.targetLead;
        if (Math.abs(drift) > 5) {
            setKomi(iso, iso.komi + drift);
            String note = "komi rebalanced by " + df.format(drift)
                    + " to " + iso.komi + ": root analysis missed part of the solution value";
            System.out.println(note);
            iso.warnings.add(note);
        }
    }

    // returns the score swing between the solved state and the solver passing
    private double calcStakesPass(IsolatedProblem iso, int visits) throws Exception {
        double threshold = Double.parseDouble(props.getProperty("redo.stake_threshold", "1.3"));
        NodeAnalyzer na = new NodeAnalyzer(props);

        KataAnalysisResult karRoot = na.analyzeNode(brain, iso.problem, visits);
        if (karRoot.isError()) {
            throw new RuntimeException("root analysis failed: " + karRoot.error);
        }

        // anchor the solved state on the best first move
        KataAnalysisResult karSolved = karRoot;
        if (!karRoot.moveInfos.isEmpty() && !karRoot.moveInfos.get(0).move.equals("pass")) {
            Point m1 = Intersection.gtp2point(karRoot.moveInfos.get(0).move);
            Node tike = iso.problem.addBasicMove(m1.x, m1.y);
            KataAnalysisResult karMove = na.analyzeNode(brain, tike, visits);
            iso.problem.removeChildNode(tike);
            if (!karMove.isError()) {
                karSolved = karMove;
            }
        }
        iso.solvedKar = karSolved;

        Node passNode = iso.problem.addBasicMove(19, 19);
        KataAnalysisResult karPass = na.analyzeNode(brain, passNode, visits);
        iso.problem.removeChildNode(passNode);
        if (karPass.isError()) {
            throw new RuntimeException("pass analysis failed: " + karPass.error);
        }

        iso.stakes.clear();
        StringBuilder sb = new StringBuilder();
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (iso.problem.board.board[x][y].stone == Intersection.EMPTY) continue;
                if (iso.isFilled(x, y)) continue;
                double od = karSolved.ownership.get(x + y * 19) - karPass.ownership.get(x + y * 19);
                if (Math.abs(od) > threshold) {
                    iso.stakes.add(new Point(x, y));
                    if (sb.length() > 0) sb.append(" ");
                    sb.append(Intersection.toGTPloc(x, y, 19));
                }
            }
        System.out.println("stakes (" + iso.stakes.size() + "): " + sb);
        return karSolved.blackScore() - karPass.blackScore();
    }
}
