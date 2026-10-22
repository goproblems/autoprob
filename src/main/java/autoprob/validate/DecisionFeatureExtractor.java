package autoprob.validate;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
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

    public KataAnalysisResult analyzeNear(Node node, int v) throws Exception {
        return na.analyzeNode(brain, node, v, nearDist, true, iso.filledStones);
    }

    // solver to move: katago's top moves, plus moves a human would naturally try. with
    // redo.solver_alternatives on, each plausible one is played and analyzed on its own, so
    // ChoiceRule can keep every move that works: a working move missing from the tree
    // would mark a correct answer wrong.
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
        int evaluated = 0;
        for (int i = 0; i < rec.candidates.size() && evaluated < solverCandidates; i++) {
            CandidateFeatures c = rec.candidates.get(i);
            if (c.point().equals("pass")) continue;
            boolean plausible = i == 0 || "human".equals(c.source)
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
                }
            } finally {
                node.removeChildNode(tike);
            }
            evaluated++;
        }
        return rec;
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
                    + ", answers " + c.answers + ", stake dist " + c.stakeDist);
        }
        return rec;
    }

    public static String describe(CandidateFeatures c) {
        if (c == null) return "?";
        if ("katago".equals(c.source)) {
            return "katago " + c.point() + " v" + c.visits + " p" + df.format(c.prior);
        }
        return "human " + c.point() + " hp" + df.format(c.humanPolicy == null ? 0 : c.humanPolicy);
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
            c.answers = countAnswers(karMove);
            if (!karMove.moveInfos.isEmpty()) {
                MoveInfo answer = karMove.moveInfos.get(0);
                c.answer = answer.move;
                c.answerPrior = answer.prior;
                if (!answer.move.equals("pass")) {
                    // how likely is a human to find the correct answer to this response?
                    Point ap = Intersection.gtp2point(answer.move);
                    c.humanAnswer = 0.0;
                    for (KataAnalysisResult.Policy hp : blendedHumanPolicy(tike)) {
                        if (hp.x == ap.x && hp.y == ap.y) {
                            c.humanAnswer = hp.policy;
                            break;
                        }
                    }
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

    // how many solver answers hold the result: katago moves its search took seriously (a
    // tenth of the top move's visits) scoring within alt_score_tolerance of the best of them.
    // 0 when the solver's best answer is to pass.
    private int countAnswers(KataAnalysisResult kar) {
        if (kar.moveInfos.isEmpty() || kar.moveInfos.get(0).move.equals("pass")) return 0;
        int sign = iso.solverColor == Intersection.BLACK ? 1 : -1;
        int top = kar.moveInfos.get(0).visits;
        double best = Double.NEGATIVE_INFINITY;
        for (MoveInfo mi : kar.moveInfos) {
            if (mi.move.equals("pass") || mi.visits < 0.1 * top) continue;
            best = Math.max(best, sign * mi.scoreLead);
        }
        int count = 0;
        for (MoveInfo mi : kar.moveInfos) {
            if (mi.move.equals("pass") || mi.visits < 0.1 * top) continue;
            if (sign * mi.scoreLead >= best - params.altScoreTolerance) count++;
        }
        return count;
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
