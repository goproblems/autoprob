package autoprob.validate;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import autoprob.KataBrain;
import autoprob.NodeAnalyzer;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;
import autoprob.validate.ProblemIsolator.IsolatedProblem;

// the expensive half of a decision: asks katago (and the human policy net) for the
// candidates at a position and the numbers behind each one. ChoiceRule then picks
// from the resulting DecisionRecord without touching the engine.
public class DecisionFeatureExtractor {
    private static final DecimalFormat df = new DecimalFormat("0.00");
    private static final int SOLVER_CANDIDATES = 5;
    // a stone's ownership change below this is left out of a candidate's status: noise
    private static final double STATUS_FLOOR = 0.5;
    // the quick look at a scanned move is noisier: it passes on to the full measurement
    // when its lead is within this much more than status_score_margin of the best
    private static final double SCAN_SLACK = 2.0;

    private final KataBrain brain;
    private final IsolatedProblem iso;
    private final NodeAnalyzer na;
    private final ChoiceParams params; // gathering limits and the threat threshold

    private final int visits;
    private final int passVisits;
    private final double nearDist;
    private final String[] humanRanks;
    private final boolean evaluateAlternatives;
    private final int solverCandidates;
    // analyses of positions after a solver move, by position, so the tree builder can
    // reuse them as the starting point of the opponent's decision
    private final Map<String, KataAnalysisResult> afterCache = new HashMap<>();

    public DecisionFeatureExtractor(Properties props, KataBrain brain, IsolatedProblem iso, ChoiceParams params) {
        this.brain = brain;
        this.iso = iso;
        this.params = params;
        this.na = new NodeAnalyzer(props);
        visits = Integer.parseInt(props.getProperty("redo.visits", "1000"));
        passVisits = Integer.parseInt(props.getProperty("redo.pass_visits", "1000"));
        nearDist = Double.parseDouble(props.getProperty("redo.near_dist", "2.1"));
        humanRanks = props.getProperty("redo.human_ranks", "5k,1d").split(",");
        evaluateAlternatives = Boolean.parseBoolean(props.getProperty("redo.solver_alternatives", "true"));
        solverCandidates = Integer.parseInt(props.getProperty("redo.solver_candidates", "6"));
    }

    // moves near the problem, and on a ko threat pattern if the board has one: the solver
    // must be able to answer a ko capture with a threat
    public KataAnalysisResult analyzeNear(Node node, int v) throws Exception {
        return na.analyzeNode(brain, node, v, nearDist, true, iso.filledStones, iso.threats);
    }

    // solver to move: katago's top moves, plus moves a human would naturally try. with
    // redo.solver_alternatives on, each plausible one is played and analyzed on its own, so
    // ChoiceRule can keep every move that works: a working move missing from the tree
    // would mark a correct answer wrong. each gets its life status against the best one,
    // and every other point near the stakes gets a quick look (see scan).
    public DecisionRecord solver(Node node, List<String> at, int v) throws Exception {
        DecisionRecord rec = newRecord(node, at, DecisionRecord.SOLVER);
        KataAnalysisResult kar = analyzeNear(node, v);
        if (kar.isError() || kar.moveInfos.isEmpty()) {
            rec.error = kar.isError() ? kar.error : "no moves returned";
            return rec;
        }
        for (MoveInfo mi : kar.moveInfos) {
            if (rec.candidates.size() >= SOLVER_CANDIDATES) break;
            CandidateFeatures c = new CandidateFeatures(rec.toMove + " " + mi.move, "katago");
            c.prior = mi.prior;
            c.visits = mi.visits;
            c.scoreLead = mi.scoreLead;
            rec.candidates.add(c);
        }
        if (!evaluateAlternatives || rec.candidates.get(0).point().equals("pass")) {
            return rec;
        }

        List<KataAnalysisResult.Policy> human = blendedHumanPolicy(node);
        for (KataAnalysisResult.Policy hp : human) {
            if (hp.policy < params.minHumanPolicy) continue;
            addCandidate(rec, node, new CandidateFeatures(
                    rec.toMove + " " + Intersection.toGTPloc(hp.x, hp.y, 19), "human"));
        }
        for (CandidateFeatures c : rec.candidates) {
            if (c.point().equals("pass")) continue;
            Point p = Intersection.gtp2point(c.point());
            c.humanPolicy = 0.0;
            for (KataAnalysisResult.Policy hp : human) {
                if (hp.x == p.x && hp.y == p.y) c.humanPolicy = hp.policy;
            }
            c.stakeDist = distanceToStakes(p);
        }

        // play each plausible candidate and see whether the solver keeps the result
        int sign = rec.toMove.equals("B") ? 1 : -1;
        Map<String, KataAnalysisResult> afterMove = new HashMap<>();
        int evaluated = 0;
        for (int i = 0; i < rec.candidates.size() && evaluated < solverCandidates; i++) {
            CandidateFeatures c = rec.candidates.get(i);
            if (c.point().equals("pass")) continue;
            // a natural human move counts even when katago listed it too, with few visits
            boolean plausible = i == 0 || "human".equals(c.source)
                    || (c.humanPolicy != null && c.humanPolicy >= params.minHumanPolicy)
                    || c.prior >= params.minResponsePrior || c.visits >= v * 0.1;
            // a move away from the fight is a tenuki, not an answer: not worth the engine time
            if (!plausible || (i > 0 && !ChoiceRule.nearFight(c, params))) continue;
            Point p = Intersection.gtp2point(c.point());
            if (!node.legalMove(p)) continue;
            Node tike = node.addBasicMove(p.x, p.y);
            try {
                KataAnalysisResult after = after(tike);
                if (after.isError()) {
                    c.error = true;
                } else {
                    c.afterLead = sign * after.blackScore();
                    afterMove.put(c.move, after);
                }
            } finally {
                node.removeChildNode(tike);
            }
            evaluated++;
        }

        // the life status after each move, against the best one's
        CandidateFeatures best = ChoiceRule.bestCandidate(rec);
        if (best == null) return rec;
        KataAnalysisResult reference = afterMove.get(best.move);
        for (CandidateFeatures c : rec.candidates) {
            KataAnalysisResult after = afterMove.get(c.move);
            if (after != null) c.statusDeltas = statusDeltas(node, after, reference);
        }
        scan(rec, node, kar, human, reference, best.afterLead);
        return rec;
    }

    // every other legal point near the stakes, however unlikely a move it is: one that
    // reaches the best move's life status is correct even when it costs points, and katago
    // and the human policy can both rate such a move very low. each gets a quick look at
    // scan_visits, and those that keep the status there are measured like the rest
    private void scan(DecisionRecord rec, Node node, KataAnalysisResult kar, List<KataAnalysisResult.Policy> human,
                      KataAnalysisResult reference, double best) throws Exception {
        if (params.scanDist < 0 || iso.stakes.isEmpty()) return;
        int sign = rec.toMove.equals("B") ? 1 : -1;
        int looked = 0;
        List<String> kept = new ArrayList<>();
        for (int y = 0; y < 19; y++)
            for (int x = 0; x < 19; x++) {
                Point p = new Point(x, y);
                if (node.board.board[x][y].stone != Intersection.EMPTY || iso.threats.contains(p)) continue;
                if (distanceToStakes(p) > params.scanDist || !node.legalMove(p)) continue;
                String gtp = Intersection.toGTPloc(x, y, 19);
                String move = rec.toMove + " " + gtp;
                // a candidate katago or the human policy listed but that was not measured gets
                // the look too
                CandidateFeatures listed = rec.candidate(move);
                if (listed != null && (listed.afterLead != null || Boolean.TRUE.equals(listed.error))) continue;
                Node tike = node.addBasicMove(x, y);
                try {
                    looked++;
                    KataAnalysisResult quick = analyzeNear(tike, params.scanVisits);
                    if (quick.isError() || sign * quick.blackScore() < best - params.statusScoreMargin - SCAN_SLACK) continue;
                    if (ChoiceRule.fateChanges(statusDeltas(node, quick, reference), params)
                            > ChoiceRule.allowedChanges(params, iso.stakes)) continue;
                    KataAnalysisResult after = after(tike);
                    if (after.isError()) continue;
                    CandidateFeatures c = listed;
                    if (c == null) {
                        c = new CandidateFeatures(move, "scan");
                        c.prior = kar.policy == null ? null : kar.policy.get(x + y * 19);
                        MoveInfo mi = kar.getMoveInfo(gtp);
                        c.visits = mi == null ? 0 : mi.visits;
                        c.humanPolicy = humanPolicyAt(human, gtp);
                        c.stakeDist = distanceToStakes(p);
                        rec.candidates.add(c);
                    }
                    c.afterLead = sign * after.blackScore();
                    c.statusDeltas = statusDeltas(node, after, reference);
                    kept.add(gtp);
                } finally {
                    node.removeChildNode(tike);
                }
            }
        if (!kept.isEmpty()) {
            System.out.println("  looked at " + looked + " more points near the stakes, measured " + String.join(", ", kept));
        }
    }

    // the ownership change of each real stone at the decision position after a move,
    // against after the reference move (this one's minus the reference's, black's view),
    // for the stones that moved more than STATUS_FLOOR
    public Map<String, Double> statusDeltas(Node node, KataAnalysisResult after, KataAnalysisResult reference) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (node.board.board[x][y].stone == Intersection.EMPTY || iso.isFilled(x, y)) continue;
                double d = after.ownership.get(x + y * 19) - reference.ownership.get(x + y * 19);
                if (Math.abs(d) > STATUS_FLOOR) out.put(Intersection.toGTPloc(x, y, 19), Math.round(d * 1000.0) / 1000.0);
            }
        return out;
    }

    // a solver move measured like the candidates: the decision's own measurement when it has
    // one, or played and measured now against the decision's best move. null when it cannot
    // be played or analyzed
    public CandidateFeatures measure(Node node, DecisionRecord rec, String move) throws Exception {
        CandidateFeatures c = rec.candidate(move);
        if (c != null && c.afterLead != null && !Boolean.TRUE.equals(c.error)) return c;
        CandidateFeatures best = ChoiceRule.bestCandidate(rec);
        Point p = Intersection.gtp2point(move.substring(2));
        if (best == null || !node.legalMove(p)) return null;
        KataAnalysisResult reference = afterMove(node, best.move);
        KataAnalysisResult after = afterMove(node, move);
        if (reference == null || after == null) return null;
        CandidateFeatures out = new CandidateFeatures(move, "measured");
        out.afterLead = (move.startsWith("B") ? 1 : -1) * after.blackScore();
        out.statusDeltas = statusDeltas(node, after, reference);
        return out;
    }

    // the analysis after a move at node, or null
    private KataAnalysisResult afterMove(Node node, String move) throws Exception {
        Point p = Intersection.gtp2point(move.substring(2));
        Node child = node.addBasicMove(p.x, p.y);
        try {
            KataAnalysisResult kar = after(child);
            return kar.isError() ? null : kar;
        } finally {
            node.removeChildNode(child);
        }
    }

    // the analysis of a position right after a solver move, at redo.visits, from the cache
    // when the solver's decision already looked at it
    public KataAnalysisResult after(Node node) throws Exception {
        String key = positionKey(node);
        KataAnalysisResult kar = afterCache.get(key);
        if (kar == null) {
            kar = analyzeNear(node, visits);
            if (!kar.isError()) afterCache.put(key, kar);
        }
        return kar;
    }

    // identifies a position regardless of the move order that reached it
    public static String positionKey(Node node) {
        StringBuilder sb = new StringBuilder(380);
        for (int y = 0; y < 19; y++)
            for (int x = 0; x < 19; x++)
                sb.append((char) ('0' + node.board.board[x][y].stone));
        sb.append(node.getToMove());
        Point ko = node.getKo();
        if (ko != null) sb.append('k').append(ko.x).append(',').append(ko.y);
        return sb.toString();
    }

    // opponent to move, analyzing the position first
    public DecisionRecord opponent(Node node, List<String> at) throws Exception {
        KataAnalysisResult kar = analyzeNear(node, visits);
        if (kar.isError()) {
            DecisionRecord rec = newRecord(node, at, DecisionRecord.OPPONENT);
            rec.error = kar.error;
            return rec;
        }
        return opponent(node, at, kar);
    }

    // opponent to move: gather plausible responses and measure how much each threatens.
    // kar is the analysis of node at redo.visits.
    public DecisionRecord opponent(Node node, List<String> at, KataAnalysisResult kar) throws Exception {
        DecisionRecord rec = newRecord(node, at, DecisionRecord.OPPONENT);
        List<KataAnalysisResult.Policy> human = blendedHumanPolicy(node);

        // katago's moves: strongest resistance, in engine order
        int added = 0;
        for (MoveInfo mi : kar.moveInfos) {
            if (added >= params.responseCandidates) break;
            if (mi.move.equals("pass")) continue;
            if (mi.prior < params.minResponsePrior && mi.visits < params.visits * 0.1) continue;
            CandidateFeatures c = new CandidateFeatures(rec.toMove + " " + mi.move, "katago");
            c.prior = mi.prior;
            c.visits = mi.visits;
            addCandidate(rec, node, c);
            added++;
        }

        // optionally natural human tries that katago may have dismissed
        if (params.humanThreatExtends) {
            for (KataAnalysisResult.Policy hp : human) {
                if (hp.policy < params.minHumanPolicy) continue;
                addCandidate(rec, node, new CandidateFeatures(
                        rec.toMove + " " + Intersection.toGTPloc(hp.x, hp.y, 19), "human"));
            }
        }

        for (CandidateFeatures c : rec.candidates) {
            Point p = Intersection.gtp2point(c.point());
            c.humanPolicy = 0.0;
            for (KataAnalysisResult.Policy hp : human) {
                if (hp.x == p.x && hp.y == p.y) c.humanPolicy = hp.policy;
            }
            c.stakeDist = distanceToStakes(p);
            measureThreat(node, c);
            System.out.println("  response candidate " + c.point() + " (" + describe(c) + "): threat " + c.threat
                    + ", answer prior " + fmt(c.answerPrior) + ", human answer " + fmt(c.humanAnswer)
                    + " (any that holds " + fmt(c.humanAnswers) + "), answers " + c.answers
                    + ", stake dist " + c.stakeDist);
        }
        return rec;
    }

    public static String describe(CandidateFeatures c) {
        if (c == null) return "?";
        if ("katago".equals(c.source)) {
            return "katago " + c.point() + " v" + c.visits + " p" + df.format(c.prior);
        }
        return c.source + " " + c.point() + " hp" + df.format(c.humanPolicy == null ? 0 : c.humanPolicy);
    }

    private DecisionRecord newRecord(Node node, List<String> at, String role) {
        DecisionRecord rec = new DecisionRecord();
        rec.at = new ArrayList<>(at);
        rec.toMove = IsolationRecord.colorLetter(node.getToMove());
        rec.role = role;
        return rec;
    }

    private void addCandidate(DecisionRecord rec, Node node, CandidateFeatures c) {
        Point p = Intersection.gtp2point(c.point());
        if (p.x >= 19 || p.y >= 19) return;
        if (rec.candidate(c.move) != null) return; // dedup
        if (!isLocal(node, p)) return;
        if (node.board.board[p.x][p.y].stone != Intersection.EMPTY) return;
        if (!node.legalMove(p)) return;
        rec.candidates.add(c);
    }

    // how many real stones change fate if this move is played and then ignored?
    // this is the measure of whether a move is a threat the solver must answer.
    // also records how obvious the solver's best answer is, to katago and to a human.
    private void measureThreat(Node node, CandidateFeatures c) throws Exception {
        Point p = Intersection.gtp2point(c.point());
        Node tike = node.addBasicMove(p.x, p.y);
        try {
            KataAnalysisResult karMove = analyzeNear(tike, passVisits);
            if (karMove.isError()) {
                c.error = true;
                c.threat = 0;
                return;
            }
            c.answerPrior = 1.0;
            c.humanAnswer = 1.0;
            c.humanAnswers = 1.0;
            List<String> holding = holdingAnswers(karMove);
            c.answers = holding.size();
            if (!karMove.moveInfos.isEmpty()) {
                MoveInfo answer = karMove.moveInfos.get(0);
                c.answer = answer.move;
                c.answerPrior = answer.prior;
                if (!answer.move.equals("pass")) {
                    // how likely is a human to find the correct answer to this response: the
                    // best answer, and any answer that holds
                    List<KataAnalysisResult.Policy> human = blendedHumanPolicy(tike);
                    c.humanAnswer = humanPolicyAt(human, answer.move);
                    double sum = 0;
                    for (String m : holding) sum += humanPolicyAt(human, m);
                    c.humanAnswers = Math.min(1.0, sum);
                }
            }
            Node passNode = tike.addBasicMove(19, 19);
            KataAnalysisResult karPass = analyzeNear(passNode, passVisits);
            tike.removeChildNode(passNode);
            if (karPass.isError()) {
                c.error = true;
                c.threat = 0;
                return;
            }
            for (int x = 0; x < 19; x++)
                for (int y = 0; y < 19; y++) {
                    if (tike.board.board[x][y].stone == Intersection.EMPTY) continue;
                    if (iso.isFilled(x, y)) continue;
                    c.putDelta(Intersection.toGTPloc(x, y, 19),
                            karMove.ownership.get(x + y * 19) - karPass.ownership.get(x + y * 19));
                }
            c.threat = ChoiceRule.countThreat(c, params, iso.stakes);
        } finally {
            node.removeChildNode(tike);
        }
    }

    // the solver answers that hold the result: katago moves its search took seriously (a
    // tenth of the top move's visits) scoring within alt_score_tolerance of the best of them.
    // none when the solver's best answer is to pass.
    private List<String> holdingAnswers(KataAnalysisResult kar) {
        List<String> out = new ArrayList<>();
        if (kar.moveInfos.isEmpty() || kar.moveInfos.get(0).move.equals("pass")) return out;
        int sign = iso.solverColor == Intersection.BLACK ? 1 : -1;
        int top = kar.moveInfos.get(0).visits;
        double best = Double.NEGATIVE_INFINITY;
        for (MoveInfo mi : kar.moveInfos) {
            if (mi.move.equals("pass") || mi.visits < 0.1 * top) continue;
            best = Math.max(best, sign * mi.scoreLead);
        }
        for (MoveInfo mi : kar.moveInfos) {
            if (mi.move.equals("pass") || mi.visits < 0.1 * top) continue;
            if (sign * mi.scoreLead >= best - params.altScoreTolerance) out.add(mi.move);
        }
        return out;
    }

    private static double humanPolicyAt(List<KataAnalysisResult.Policy> policy, String gtp) {
        Point p = Intersection.gtp2point(gtp);
        for (KataAnalysisResult.Policy hp : policy) {
            if (hp.x == p.x && hp.y == p.y) return hp.policy;
        }
        return 0.0;
    }

    private int distanceToStakes(Point p) {
        int min = 100;
        for (Point s : iso.stakes) {
            min = Math.min(min, Math.max(Math.abs(s.x - p.x), Math.abs(s.y - p.y)));
        }
        return min == 100 ? 0 : min;
    }

    // within near distance of a real (non-filled) stone
    private boolean isLocal(Node node, Point p) {
        int idist = (int) Math.ceil(nearDist);
        for (int dx = -idist; dx <= idist; dx++)
            for (int dy = -idist; dy <= idist; dy++) {
                int x = p.x + dx, y = p.y + dy;
                if (!node.board.inBoard(x, y)) continue;
                if (node.board.board[x][y].stone == Intersection.EMPTY) continue;
                if (iso.isFilled(x, y)) continue;
                if (Math.max(Math.abs(dx), Math.abs(dy)) <= nearDist) return true;
            }
        return false;
    }

    // top human policy moves blended over the configured ranks
    private List<KataAnalysisResult.Policy> blendedHumanPolicy(Node node) throws Exception {
        List<KataAnalysisResult.Policy> merged = new ArrayList<>();
        for (String rank : humanRanks) {
            KataAnalysisResult kar = na.analyzeNode(brain, node, 1, null, rank.trim());
            if (kar.isError() || kar.humanPolicy == null) continue;
            for (KataAnalysisResult.Policy p : kar.getTopPolicy(10, kar.humanPolicy)) {
                boolean found = false;
                for (KataAnalysisResult.Policy m : merged) {
                    if (m.x == p.x && m.y == p.y) {
                        found = true;
                        if (p.policy > m.policy) m.policy = p.policy; // keep max over ranks
                        break;
                    }
                }
                if (!found) merged.add(p);
            }
        }
        merged.sort((a, b) -> Double.compare(b.policy, a.policy));
        return merged;
    }

    private static String fmt(Double d) {
        return d == null ? "-" : df.format(d);
    }
}
