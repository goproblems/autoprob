package autoprob.validate;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import autoprob.KataBrain;
import autoprob.NodeAnalyzer;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;
import autoprob.validate.ProblemIsolator.IsolatedProblem;

// generates the main correct solution line for an isolated problem.
// the solver plays katago's best move; the opponent plays the strongest resistance
// that still constitutes a real threat. the path ends when the opponent has no
// plausible move left that the solver must answer.
public class SolutionPathGenerator {
    private static final DecimalFormat df = new DecimalFormat("0.00");

    private final Properties props;
    private final KataBrain brain;
    private final IsolatedProblem iso;
    private final NodeAnalyzer na;

    private final int visits;
    private final int visitsRoot;
    private final int passVisits;
    private final double nearDist;
    private final int maxMoves;
    private final int threatStones;
    private final double threatOwnershipThreshold;
    private final int responseCandidates;
    private final double minResponsePrior;
    private final double minHumanPolicy;
    private final String[] humanRanks;
    private final boolean humanThreatExtends;

    public static class GenResult {
        public Node endNode; // last solver move, marked RIGHT
        public boolean endedNaturally = true;
        public List<String> notes = new ArrayList<>();
    }

    // a candidate opponent response
    private static class Candidate {
        Point p;
        String desc;

        Candidate(Point p, String desc) {
            this.p = p;
            this.desc = desc;
        }
    }

    public SolutionPathGenerator(Properties props, KataBrain brain, IsolatedProblem iso) {
        this.props = props;
        this.brain = brain;
        this.iso = iso;
        this.na = new NodeAnalyzer(props);

        visits = Integer.parseInt(props.getProperty("redo.visits", "1000"));
        visitsRoot = Integer.parseInt(props.getProperty("redo.visits_root", "2000"));
        passVisits = Integer.parseInt(props.getProperty("redo.pass_visits", "1000"));
        nearDist = Double.parseDouble(props.getProperty("redo.near_dist", "2.1"));
        maxMoves = Integer.parseInt(props.getProperty("redo.max_moves", "20"));
        threatStones = Integer.parseInt(props.getProperty("redo.threat_stones", "4"));
        threatOwnershipThreshold = Double.parseDouble(props.getProperty("redo.threat_ownership_threshold", "1.1"));
        responseCandidates = Integer.parseInt(props.getProperty("redo.response_candidates", "4"));
        minResponsePrior = Double.parseDouble(props.getProperty("redo.min_response_prior", "0.02"));
        minHumanPolicy = Double.parseDouble(props.getProperty("redo.min_human_policy", "0.05"));
        humanRanks = props.getProperty("redo.human_ranks", "5k,1d").split(",");
        humanThreatExtends = Boolean.parseBoolean(props.getProperty("redo.human_threat_extends", "true"));
    }

    private KataAnalysisResult analyzeNear(Node node, int v) throws Exception {
        return na.analyzeNode(brain, node, v, nearDist, true, iso.filledStones);
    }

    public GenResult generateMainLine() throws Exception {
        GenResult result = new GenResult();
        Node cur = iso.problem;
        int moveCount = 0;

        while (true) {
            // solver to move
            KataAnalysisResult kar = analyzeNear(cur, moveCount == 0 ? visitsRoot : visits);
            if (kar.isError() || kar.moveInfos.isEmpty()) {
                result.notes.add("analysis failed at move " + (moveCount + 1) + ", truncating");
                result.endedNaturally = false;
                break;
            }
            MoveInfo top = kar.moveInfos.get(0);
            if (top.move.equals("pass")) {
                result.notes.add("solver wants to pass at move " + (moveCount + 1) + ", ending");
                break;
            }
            Point p = Intersection.gtp2point(top.move);
            Node solverNode = cur.addBasicMove(p.x, p.y);
            moveCount++;
            System.out.println("move " + moveCount + " (solver): " + top.move
                    + " (policy " + df.format(top.prior) + ", visits " + top.visits
                    + ", score " + df.format(top.scoreLead) + ")");
            result.endNode = solverNode;

            KataAnalysisResult karAfter = analyzeNear(solverNode, visits);
            if (karAfter.isError()) {
                result.notes.add("analysis failed after move " + moveCount + ", truncating");
                result.endedNaturally = false;
                break;
            }
            checkStakesHeld(karAfter, moveCount, result);

            if (moveCount >= maxMoves) {
                result.notes.add("hit max moves (" + maxMoves + "), truncating");
                result.endedNaturally = false;
                break;
            }

            // ending check: does the opponent have any real threat left?
            Candidate response = chooseOpponentResponse(solverNode, karAfter);
            if (response == null) {
                System.out.println("no opponent threat remains: path ends after move " + moveCount);
                break;
            }

            Node oppNode = solverNode.addBasicMove(response.p.x, response.p.y);
            moveCount++;
            System.out.println("move " + moveCount + " (opponent): "
                    + Intersection.toGTPloc(response.p.x, response.p.y, 19) + " (" + response.desc + ")");
            cur = oppNode;
        }

        if (result.endNode != null) {
            result.endNode.result = Intersection.RIGHT;
        }
        return result;
    }

    // warn if the stake stones are no longer settled the way the root solution expects
    private void checkStakesHeld(KataAnalysisResult kar, int moveCount, GenResult result) {
        int flipped = 0;
        for (Point s : iso.stakes) {
            double rootOwn = iso.rootKar.ownership.get(s.x + s.y * 19);
            double nowOwn = kar.ownership.get(s.x + s.y * 19);
            if (rootOwn * nowOwn < 0 && Math.abs(nowOwn) > 0.3) {
                flipped++;
            }
        }
        if (flipped * 3 > iso.stakes.size() && !iso.stakes.isEmpty()) {
            String note = "WARNING: " + flipped + "/" + iso.stakes.size()
                    + " stake stones flipped after move " + moveCount;
            System.out.println(note);
            result.notes.add(note);
        }
    }

    // pick the opponent response: the strongest resistance that is still a real threat.
    // returns null if nothing threatens, meaning the path is over.
    private Candidate chooseOpponentResponse(Node node, KataAnalysisResult kar) throws Exception {
        List<Candidate> candidates = new ArrayList<>();

        // katago's moves first: strongest resistance, in engine order
        int added = 0;
        for (MoveInfo mi : kar.moveInfos) {
            if (added >= responseCandidates) break;
            if (mi.move.equals("pass")) continue;
            if (mi.prior < minResponsePrior && mi.visits < visits * 0.1) continue;
            Point p = Intersection.gtp2point(mi.move);
            addCandidate(candidates, node, p, "katago " + mi.move + " v" + mi.visits + " p" + df.format(mi.prior));
            added++;
        }

        // then natural human tries that katago may have dismissed
        if (humanThreatExtends) {
            for (KataAnalysisResult.Policy hp : blendedHumanPolicy(node)) {
                if (hp.policy < minHumanPolicy) continue;
                Point p = new Point(hp.x, hp.y);
                addCandidate(candidates, node, p, "human " + Intersection.toGTPloc(hp.x, hp.y, 19)
                        + " hp" + df.format(hp.policy));
            }
        }

        for (Candidate c : candidates) {
            int threat = calcPassDelta(node, c.p);
            System.out.println("  response candidate " + Intersection.toGTPloc(c.p.x, c.p.y, 19)
                    + " (" + c.desc + "): threat " + threat);
            if (threat >= threatStones) {
                return c;
            }
        }
        return null;
    }

    private void addCandidate(List<Candidate> candidates, Node node, Point p, String desc) {
        if (p.x >= 19 || p.y >= 19) return;
        for (Candidate c : candidates) {
            if (c.p.equals(p)) return; // dedup
        }
        if (!isLocal(node, p)) return;
        if (node.board.board[p.x][p.y].stone != Intersection.EMPTY) return;
        if (!node.legalMove(p)) return;
        candidates.add(new Candidate(p, desc));
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
            for (KataAnalysisResult.Policy p : kar.getTopPolicy(5, kar.humanPolicy)) {
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

    // how many real stones change fate if this move is played and then ignored?
    // this is the measure of whether a move is a threat the solver must answer.
    private int calcPassDelta(Node node, Point p) throws Exception {
        Node tike = node.addBasicMove(p.x, p.y);
        try {
            KataAnalysisResult karMove = analyzeNear(tike, passVisits);
            if (karMove.isError()) return 0;
            Node passNode = tike.addBasicMove(19, 19);
            KataAnalysisResult karPass = analyzeNear(passNode, passVisits);
            tike.removeChildNode(passNode);
            if (karPass.isError()) return 0;

            int delta = 0;
            for (int x = 0; x < 19; x++)
                for (int y = 0; y < 19; y++) {
                    if (tike.board.board[x][y].stone == Intersection.EMPTY) continue;
                    if (iso.isFilled(x, y)) continue;
                    double od = karMove.ownership.get(x + y * 19) - karPass.ownership.get(x + y * 19);
                    if (Math.abs(od) > threatOwnershipThreshold) {
                        delta++;
                    }
                }
            return delta;
        } finally {
            node.removeChildNode(tike);
        }
    }
}
