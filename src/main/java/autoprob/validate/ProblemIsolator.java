package autoprob.validate;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import autoprob.KataBrain;
import autoprob.go.Board;
import autoprob.NodeAnalyzer;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

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
        public KataAnalysisResult passKar;   // analysis after the solver passes
        public double targetLead; // black score the komi balance aims for with correct play
        public ArrayList<Point> stakes = new ArrayList<>(); // stones whose life depends on solving
        public ArrayList<String> warnings = new ArrayList<>();
        // with ko threats on: the points of the threat pattern, where the side to move may
        // play even though they are far from the problem, and whose threats they are
        public List<Point> threats = new ArrayList<>();
        public int threatColor;

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

        // fill the rest of the board: balanced static stones, or with ko threats, katrain's
        // tsumego frame (its threat only works inside its own fill). threats for the solver
        // make a ko the solver starts count as won, as a problem whose answer is a ko intends;
        // threats for the opponent make a solver move that needs to win a ko fail, so a clean
        // answer comes out ahead of a ko
        int gap = Integer.parseInt(props.getProperty("redo.gap", "4"));
        if (Boolean.parseBoolean(props.getProperty("redo.ko_threats", "false"))) {
            boolean forOpponent = props.getProperty("redo.ko_threats_for", "solver").equals("opponent");
            int opponent = iso.solverColor == Intersection.BLACK ? Intersection.WHITE : Intersection.BLACK;
            int threatColor = forOpponent ? opponent : iso.solverColor;
            iso.threats = new TsumegoFrame().build(problem.board, gap, threatColor, 7.5, true, iso.filledStones);
            iso.threatColor = threatColor;
            System.out.println("tsumego frame with ko threats for " + Intersection.color2name(threatColor)
                    + (forOpponent ? " (the opponent)" : "")
                    + (iso.threats.isEmpty() ? ": WARNING, no room for the threat pattern" : ""));
            if (iso.threats.isEmpty()) iso.warnings.add("no room for the ko threat pattern");
        } else {
            new BalancedFortress().build(problem.board, gap, iso.filledStones);
        }

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

    // what is at stake when the solver wins every ko. on a board without threats katago counts
    // a ko as won by whoever takes it first, so in a problem whose answer is a ko the opponent
    // takes first, its solved state leaves the stones the ko decides alive for the opponent,
    // and they never come out at stake: in gp-36582 only the two stones the answer cuts off
    // did, while the corner its ko kills stayed black. katago does not even play the line on
    // to the ko, since it expects the opponent to take it. so the solver's likely starts
    // (katago's top first moves, and the hints: the author's lines, which can only add to what
    // is at stake, never decide what a move comes to) are played out with the solver winning
    // every ko, and the one that gains the solver the most of the problem area is the solved
    // state. when it gains the solver more than katago's own solved state, the stones of the
    // group it decides join the ones katago found at stake, and it is the solved state from
    // then on. this also steadies the isolation: katago's search at the start does not
    // always find the answer (gp-10330, where some runs had only the solver's stones inside at
    // stake and not the group the answer kills)
    public void settleUnderKos(IsolatedProblem iso, RegimeWalker walker, List<List<String>> hints) throws Exception {
        if (iso.passKar == null || iso.passKar.isError()) return;
        KataAnalysisResult adopted = koStakes(iso, walker, hints);
        // komi balanced on the new solved state, the solver just ahead there, so that katago
        // plays for that result too. a wider margin was tried, halfway between the solved and
        // the failed position, so that points would not sway it: but then katago, ahead by far
        // whatever it does, takes a line through a ko it expects to win as readily as a clean
        // one, and the regime takes the ko away (gp-32711's T19 and gp-36582's S17 came out a
        // level lower). just ahead, keeping the status is what keeps it winning
        if (adopted == null) return;
        setKomi(iso, iso.komi + adopted.blackScore() - iso.targetLead);
        System.out.println("komi " + iso.komi + ", balanced on that");
    }

    // the solved state with the solver winning every ko, when it puts more at stake than
    // katago's own (the stakes are taken from it then), or null
    private KataAnalysisResult koStakes(IsolatedProblem iso, RegimeWalker walker, List<List<String>> hints) throws Exception {
        int visits = Integer.parseInt(props.getProperty("redo.visits_root", "2000"));
        double threshold = Double.parseDouble(props.getProperty("redo.stake_threshold", "1.3"));
        String color = IsolationRecord.colorLetter(iso.solverColor);
        Set<List<String>> starts = new LinkedHashSet<>();
        KataAnalysisResult root = walker.analyze(List.of(), visits);
        if (!root.isError() && root.moveInfos != null) {
            for (MoveInfo mi : root.moveInfos) {
                if (starts.size() >= 4) break;
                if (!mi.move.equalsIgnoreCase("pass")) starts.add(List.of(color + " " + mi.move));
            }
        }
        starts.addAll(hints);
        boolean[][] zone = RegimeWalker.zone(iso);
        int sign = iso.solverColor == Intersection.BLACK ? 1 : -1;
        KataAnalysisResult best = null;
        String bestMove = null;
        double bestArea = Double.NEGATIVE_INFINITY;
        for (List<String> start : starts) {
            if (start.isEmpty() || !start.get(0).startsWith(color) || walker.legalPrefix(start) < start.size()) continue;
            RegimeWalker.Walk w = walker.walk(start, iso.solverColor, visits);
            if (w.kar() == null || w.kar().isError() || w.kar().ownership == null) continue;
            double area = 0;
            for (int x = 0; x < 19; x++)
                for (int y = 0; y < 19; y++) {
                    if (zone[x][y]) area += sign * w.kar().ownership.get(x + y * 19);
                }
            if (area > bestArea) {
                bestArea = area;
                best = w.kar();
                bestMove = String.join(" ", start);
            }
        }
        if (best == null) return null;
        double solvedArea = 0;
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (zone[x][y]) solvedArea += sign * iso.solvedKar.ownership.get(x + y * 19);
            }
        if (bestArea <= solvedArea + threshold) return null; // no more than katago found on its own
        // what the line decides, by color: the group the ko is for is the bulk of it, and the
        // other side's stones in it are the means, captured on the way or given up, which a
        // different line may well do without (gp-62121, where the author's line captures
        // white's stones inside black's eye space as black lives)
        List<Point> swing = new ArrayList<>();
        int black = 0, white = 0;
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                int stone = iso.problem.board.board[x][y].stone;
                if (stone == Intersection.EMPTY || iso.isFilled(x, y)) continue;
                double od = best.ownership.get(x + y * 19) - iso.passKar.ownership.get(x + y * 19);
                if (Math.abs(od) <= threshold) continue;
                swing.add(new Point(x, y));
                if (stone == Intersection.BLACK) black++;
                else white++;
            }
        int bulk = black >= white ? Intersection.BLACK : Intersection.WHITE;
        List<Point> stakes = new ArrayList<>(iso.stakes);
        for (Point p : swing) {
            if (iso.problem.board.board[p.x][p.y].stone == bulk && !stakes.contains(p)) stakes.add(p);
        }
        if (stakes.size() == iso.stakes.size()) return null;
        StringBuilder sb = new StringBuilder();
        for (Point p : stakes) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(Intersection.toGTPloc(p.x, p.y, 19));
        }
        iso.stakes.clear();
        iso.stakes.addAll(stakes);
        iso.solvedKar = best;
        String note = "with the solver winning every ko (after " + bestMove + "), more is at stake: " + stakes.size()
                + " stones, " + sb;
        System.out.println(note);
        iso.warnings.add(note);
        return best;
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

        iso.passKar = karPass;
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
