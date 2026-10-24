package autoprob.validate;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import autoprob.go.Intersection;

// picks the moves (or END) a decision puts in the solution tree, from its recorded
// candidates. pure: no engine calls, so the same function drives live generation, case
// replay, and offline tuning over stored candidates.
public class ChoiceRule {
    private static final DecimalFormat df = new DecimalFormat("0.00");

    public static class Choice {
        public final String move;       // the main choice, with color, or END
        public final List<String> also; // further moves that branch the tree here
        public final String reason;

        public Choice(String move, String reason) {
            this(move, new ArrayList<>(), reason);
        }

        public Choice(String move, List<String> also, String reason) {
            this.move = move;
            this.also = also;
            this.reason = reason;
        }

        public boolean isEnd() {
            return move.equals(DecisionRecord.END);
        }

        public List<String> moves() {
            List<String> out = new ArrayList<>();
            out.add(move);
            out.addAll(also);
            return out;
        }
    }

    public static Choice decide(DecisionRecord rec, ChoiceParams p, List<Point> stakes) {
        return rec.role.equals(DecisionRecord.SOLVER) ? chooseSolver(rec, p, stakes) : chooseOpponent(rec, p, stakes);
    }

    public static Choice chooseSolver(DecisionRecord rec, ChoiceParams p) {
        return chooseSolver(rec, p, null);
    }

    // the solver plays every move that works: in a problem, any move that solves it must be
    // in the tree or a correct answer gets marked wrong. a candidate works when it reaches
    // the life status of the best reliable candidate (see works and bestCandidate), and,
    // unless it is katago's top move, it is within alt_region_dist of a stake stone: a move
    // away from the fight is a tenuki, not an answer to the problem. the main choice is
    // katago's top move when it works. a top move of pass ends the line.
    public static Choice chooseSolver(DecisionRecord rec, ChoiceParams p, List<Point> stakes) {
        if (rec.candidates.isEmpty()) {
            return new Choice(DecisionRecord.END, "no solver moves");
        }
        CandidateFeatures top = rec.candidates.get(0);
        if (top.point().equals("pass")) {
            return new Choice(DecisionRecord.END, "solver wants to pass");
        }
        double best = bestAfterLead(rec);
        if (Double.isNaN(best)) {
            // alternatives were not evaluated: the top move alone
            return new Choice(top.move, "katago's top move");
        }
        List<String> working = new ArrayList<>();
        for (CandidateFeatures c : rec.candidates) {
            if (c != top && !nearFight(c, p)) continue;
            if (works(c, best, p, stakes, rec.toMove)) working.add(c.move);
        }
        if (working.isEmpty()) {
            return new Choice(top.move, "katago's top move (no candidate evaluated)");
        }
        // a clean result comes ahead of a ko, as the site ranks them: a move katago's line
        // fights a ko after does not count when another working move's line fights none
        int kos = 0;
        if (working.stream().anyMatch(m -> rec.candidate(m).koFight == null)) {
            kos = (int) working.stream().filter(m -> rec.candidate(m).koFight != null).count();
            working.removeIf(m -> rec.candidate(m).koFight != null);
        }
        // katago's top move stays the main line when it works
        if (working.remove(top.move)) working.add(0, top.move);
        int found = working.size();
        boolean capped = p.maxSolverMoves > 0 && found > p.maxSolverMoves;
        if (capped) working = new ArrayList<>(working.subList(0, p.maxSolverMoves));
        String main = working.remove(0);
        String reason = (found == 1 ? "the only move that works"
                : found + " moves work" + (capped ? ", kept " + p.maxSolverMoves : ""))
                + (kos > 0 ? " (" + kos + " more only through a ko)" : "");
        return new Choice(main, working, reason);
    }

    // a move works when it reaches the life status the best move does, whatever it costs in
    // points: goproblems marks any clean kill or life correct in a standard problem (see
    // sameStatus); and the lead may fall up to status_score_margin behind the best, past
    // which some status change no stone shows is likely (the opponent living somewhere new).
    // older records without a status compare points only, within alt_score_tolerance.
    // solver is the solver's color letter
    static boolean works(CandidateFeatures c, double best, ChoiceParams p, List<Point> stakes, String solver) {
        if (c.afterLead == null || Boolean.TRUE.equals(c.error) || c.point().equals("pass")) return false;
        if (c.statusDeltas == null) return c.afterLead >= best - p.altScoreTolerance;
        return sameStatus(c.statusDeltas, p, stakes, solver) && c.afterLead >= best - p.statusScoreMargin;
    }

    // the same life status as the best move: none of the opponent's stones lives that the
    // best move kills (a seki, or stones saved), and at most status_stones of the solver's
    // own die that it saves, fewer than half the stones at stake, since losing a stone or
    // two is not wrong. a stone changes fate when its ownership moves more than
    // status_threshold; a change for the better does not count against a move
    static boolean sameStatus(Map<String, Double> statusDeltas, ChoiceParams p, List<Point> stakes, String solver) {
        int lost = 0;
        for (Map.Entry<String, Double> e : statusDeltas.entrySet()) {
            if (e.getValue() >= -p.statusThreshold) continue;
            if (!e.getKey().startsWith(solver + " ")) return false;
            lost++;
        }
        return lost <= allowedChanges(p, stakes);
    }

    // the stones that change fate for the worse
    static int fateChanges(Map<String, Double> statusDeltas, ChoiceParams p) {
        int n = 0;
        for (double d : statusDeltas.values()) {
            if (d < -p.statusThreshold) n++;
        }
        return n;
    }

    // a stone or two may change fate, but never half the stones at stake
    static int allowedChanges(ChoiceParams p, List<Point> stakes) {
        if (stakes == null || stakes.isEmpty()) return p.statusStones;
        return Math.min(p.statusStones, (stakes.size() - 1) / 2);
    }

    static boolean nearFight(CandidateFeatures c, ChoiceParams p) {
        return p.altRegionDist < 0 || c.stakeDist == null || c.stakeDist <= p.altRegionDist;
    }

    // the yardstick a solver move is measured against: the reliable candidate with the best
    // lead after it, or null when nothing was evaluated. reliable means katago's top move or
    // a katago move its search gave at least a tenth of the top move's visits. a move the
    // search barely looked at can come back with an inflated lead (the search after it
    // can wander into a line that flatters it) and must not push out the real answer.
    static CandidateFeatures bestCandidate(DecisionRecord rec) {
        if (rec.candidates.isEmpty()) return null;
        CandidateFeatures best = null;
        CandidateFeatures top = rec.candidates.get(0);
        for (CandidateFeatures c : rec.candidates) {
            if (c.afterLead == null || Boolean.TRUE.equals(c.error) || c.point().equals("pass")) continue;
            boolean reliable = c == top || ("katago".equals(c.source) && c.visits != null && top.visits != null
                    && c.visits >= 0.1 * top.visits);
            if (!reliable) continue;
            if (best == null || c.afterLead > best.afterLead) best = c;
        }
        return best;
    }

    // the best reliable candidate's lead, or NaN
    static double bestAfterLead(DecisionRecord rec) {
        CandidateFeatures best = bestCandidate(rec);
        return best == null ? Double.NaN : best.afterLead;
    }

    // the opponent response for the main line: the strongest resistance that is still a
    // real threat. a correct path continues only while the opponent has a move the solver
    // must answer; lesser tries belong in refutation branches. END when nothing threatens.
    // with opponent_branches above 1, the next most testing severe threats branch too.
    public static Choice chooseOpponent(DecisionRecord rec, ChoiceParams p, List<Point> stakes) {
        List<CandidateFeatures> candidates = gather(rec, p);
        if (candidates.isEmpty()) {
            return new Choice(DecisionRecord.END, "no plausible opponent move");
        }

        if (p.extendMode.equals("primary")) {
            // only the opponent's best move may extend the main line
            CandidateFeatures c = candidates.get(0);
            int threat = countThreat(c, p, stakes);
            if (threat >= p.threatStones && realTry(c, p)) {
                return new Choice(c.move, "primary response " + c.point() + ", threat " + threat);
            }
            return new Choice(DecisionRecord.END, "primary response " + c.point()
                    + (threat >= p.threatStones ? " is answered by " + c.answers + " moves"
                    : " only threatens " + threat + " stones"));
        }

        if (p.responseCriterion.equals("order")) {
            // the first candidates that still threaten extend the tree, in katago's order
            List<String> picked = new ArrayList<>();
            for (CandidateFeatures c : candidates) {
                if (countThreat(c, p, stakes) >= p.threatStones && realTry(c, p)) picked.add(c.move);
                if (picked.size() >= Math.max(1, p.opponentBranches)) break;
            }
            if (picked.isEmpty()) return new Choice(DecisionRecord.END, "no opponent threat remains");
            String main = picked.remove(0);
            return new Choice(main, picked, "first threatening response " + main.substring(2)
                    + ", threat " + countThreat(rec.candidate(main), p, stakes));
        }

        // choose among the sufficiently severe threats
        List<CandidateFeatures> threatening = new ArrayList<>();
        int maxThreat = 0;
        int easy = 0;
        for (CandidateFeatures c : candidates) {
            int threat = countThreat(c, p, stakes);
            if (threat < p.threatStones) continue;
            if (!realTry(c, p)) {
                easy++;
                continue;
            }
            threatening.add(c);
            maxThreat = Math.max(maxThreat, threat);
        }
        if (threatening.isEmpty()) {
            return new Choice(DecisionRecord.END, easy > 0
                    ? "no testing threat remains (" + easy + " threat" + (easy == 1 ? "" : "s") + " any of several answers handle)"
                    : "no opponent threat remains");
        }
        // the line also ends when the real tries left are routine. authors stop once the
        // result is clear rather than play out replies everyone finds, though they still
        // show a single last resistance
        if (p.endOnObvious && threatening.size() >= Math.max(1, p.routineMinTries)
                && threatening.stream().allMatch(c -> obviousAnswer(c, p))) {
            return new Choice(DecisionRecord.END, "only routine tries remain (obvious answers to "
                    + String.join(", ", threatening.stream().map(CandidateFeatures::point).toList()) + ")");
        }
        double severeFloor = maxThreat * p.severeThreatRatio;
        List<CandidateFeatures> severe = new ArrayList<>();
        for (CandidateFeatures c : threatening) {
            if (countThreat(c, p, stakes) >= severeFloor) severe.add(c);
        }
        // rank by the criterion: repeatedly take the best of what is left, so ties keep
        // their candidate order exactly as a single pass would
        List<CandidateFeatures> ranked = new ArrayList<>();
        while (!severe.isEmpty() && ranked.size() < Math.max(1, p.opponentBranches)) {
            CandidateFeatures best = null;
            for (CandidateFeatures c : severe) {
                if (best == null || better(c, best, p)) best = c;
            }
            ranked.add(best);
            severe.remove(best);
        }
        CandidateFeatures best = ranked.get(0);
        List<String> also = new ArrayList<>();
        for (int i = 1; i < ranked.size(); i++) also.add(ranked.get(i).move);
        return new Choice(best.move, also, "chose response " + best.point() + " (human " + df.format(humanPolicy(best))
                + ", threat " + countThreat(best, p, stakes) + ", human answer " + df.format(answerChance(best)) + ")"
                + (also.isEmpty() ? "" : ", also " + String.join(", ", also.stream().map(m -> m.substring(2)).toList())));
    }

    // a threat is a real try only if few solver answers handle it: when many moves answer
    // it, it does not test anything and an author would not put it in the tree. unknown
    // (older records) counts as a real try.
    static boolean realTry(CandidateFeatures c, ChoiceParams p) {
        if (p.maxAnswers <= 0 || c.answers == null) return true;
        return c.answers >= 1 && c.answers <= p.maxAnswers;
    }

    // would a small change in the numbers change this decision? engine noise between runs
    // moves scores by a fraction of a point, human policies by a few hundredths and threat
    // counts by a stone, so a decision that changes under such nudges is a close call and
    // may not reproduce on replay.
    public static boolean isFragile(DecisionRecord rec, ChoiceParams p, List<Point> stakes) {
        if (rec.role.equals(DecisionRecord.SOLVER)) {
            double best = bestAfterLead(rec);
            if (Double.isNaN(best)) {
                // katago orders by visits: a runner-up with comparable visits can take over
                if (rec.candidates.size() < 2) return false;
                Integer top = rec.candidates.get(0).visits, second = rec.candidates.get(1).visits;
                return top != null && second != null && second >= 0.6 * top;
            }
            // a candidate within half a point of the works / does not work line, or with a
            // stone's ownership near the line between keeping and changing its fate
            CandidateFeatures top = rec.candidates.get(0);
            for (CandidateFeatures c : rec.candidates) {
                if (c.afterLead == null || Boolean.TRUE.equals(c.error) || c.afterLead == best) continue;
                if (c != top && !nearFight(c, p)) continue;
                double line = best - (c.statusDeltas != null ? p.statusScoreMargin : p.altScoreTolerance);
                if (Math.abs(c.afterLead - line) < 0.5) return true;
                if (c.statusDeltas == null) continue;
                for (double d : c.statusDeltas.values()) {
                    if (Math.abs(Math.abs(d) - p.statusThreshold) < 0.2) return true;
                }
            }
            return false;
        }
        List<String> base = chooseOpponent(rec, p, stakes).moves();
        for (int d : new int[]{-1, 1}) {
            ChoiceParams q = p.copy();
            q.threatStones += d;
            if (!chooseOpponent(rec, q, stakes).moves().equals(base)) return true;
        }
        for (CandidateFeatures c : rec.candidates) {
            Double humanAnswer = c.humanAnswer, humanAnswers = c.humanAnswers, humanPolicy = c.humanPolicy;
            try {
                for (double d : new double[]{-0.05, 0.05}) {
                    c.humanAnswer = humanAnswer == null ? null : Math.max(0, humanAnswer + d);
                    c.humanAnswers = humanAnswers == null ? null : Math.max(0, humanAnswers + d);
                    if (!chooseOpponent(rec, p, stakes).moves().equals(base)) return true;
                }
                c.humanAnswer = humanAnswer;
                c.humanAnswers = humanAnswers;
                for (double d : new double[]{-0.03, 0.03}) {
                    c.humanPolicy = humanPolicy == null ? null : Math.max(0, humanPolicy + d);
                    if (!chooseOpponent(rec, p, stakes).moves().equals(base)) return true;
                }
            } finally {
                c.humanAnswer = humanAnswer;
                c.humanAnswers = humanAnswers;
                c.humanPolicy = humanPolicy;
            }
        }
        return false;
    }

    // re-apply the gathering limits to the recorded candidates. with the settings used
    // when recording this keeps every candidate in its original order; tighter settings
    // narrow the list (looser ones cannot add candidates that were never analyzed)
    static List<CandidateFeatures> gather(DecisionRecord rec, ChoiceParams p) {
        List<CandidateFeatures> out = new ArrayList<>();
        int added = 0;
        for (CandidateFeatures c : rec.candidates) {
            if (!"katago".equals(c.source)) continue;
            if (added >= p.responseCandidates) break;
            if (c.prior != null && c.visits != null
                    && c.prior < p.minResponsePrior && c.visits < p.visits * 0.1) continue;
            out.add(c);
            added++;
        }
        if (p.humanThreatExtends) {
            List<CandidateFeatures> human = new ArrayList<>();
            for (CandidateFeatures c : rec.candidates) {
                if (out.contains(c)) continue;
                if (humanPolicy(c) >= p.minHumanPolicy) human.add(c);
            }
            human.sort(Comparator.comparingDouble(c -> -humanPolicy(c)));
            out.addAll(human);
        }
        return out;
    }

    // is candidate a better main line response than the current best?
    private static boolean better(CandidateFeatures c, CandidateFeatures best, ChoiceParams p) {
        if (p.responseCriterion.equals("testing")) {
            // crafted problems answer the most testing resistance: the one whose
            // correct refutation is hardest for a human to find, among the ones a
            // human would play. plain liberty fills with automatic answers are
            // auxiliary. compare in coarse bands so near-equal scores fall through
            // to human naturalness.
            int cBand = (int) Math.floor(testingScore(c, p) / p.testingBand);
            int bestBand = (int) Math.floor(testingScore(best, p) / p.testingBand);
            if (cBand != bestBand) {
                return cBand < bestBand;
            }
        }
        if (p.responseCriterion.equals("local")) {
            int cDist = c.stakeDist == null ? 0 : c.stakeDist;
            int bestDist = best.stakeDist == null ? 0 : best.stakeDist;
            if (cDist != bestDist) {
                return cDist < bestDist;
            }
        }
        return humanPolicy(c) > humanPolicy(best);
    }

    // how many real stones change fate if this move is played and then ignored
    public static int countThreat(CandidateFeatures c, ChoiceParams p, List<Point> stakes) {
        return countThreat(c, p.threatOwnershipThreshold, p.threatRegionDist, stakes);
    }

    public static int countThreat(CandidateFeatures c, double threshold, int regionDist, List<Point> stakes) {
        if (c == null || Boolean.TRUE.equals(c.error) || c.deltas == null) return 0;
        int count = 0;
        for (Map.Entry<String, Double> e : c.deltas.entrySet()) {
            if (Math.abs(e.getValue()) <= threshold) continue;
            if (regionDist >= 0 && !nearStakes(Intersection.gtp2point(e.getKey()), regionDist, stakes)) continue;
            count++;
        }
        return count;
    }

    private static boolean nearStakes(Point p, int dist, List<Point> stakes) {
        if (stakes == null || stakes.isEmpty()) return true; // no stake info: count everywhere
        for (Point s : stakes) {
            if (Math.max(Math.abs(s.x - p.x), Math.abs(s.y - p.y)) <= dist) return true;
        }
        return false;
    }

    private static double humanPolicy(CandidateFeatures c) {
        return c.humanPolicy == null ? 0 : c.humanPolicy;
    }

    private static double humanAnswer(CandidateFeatures c) {
        return c.humanAnswer == null ? 1.0 : c.humanAnswer;
    }

    // how likely a human is to answer this resistance correctly: any answer that holds
    // counts. older records only have the best answer's policy
    static double answerChance(CandidateFeatures c) {
        return c.humanAnswers != null ? c.humanAnswers : humanAnswer(c);
    }

    // katago is sure of the answer and a human finds one that holds. unknown (records from
    // before human answers) is not obvious
    static boolean obviousAnswer(CandidateFeatures c, ChoiceParams p) {
        Double chance = c.humanAnswers != null ? c.humanAnswers : c.humanAnswer;
        return c.answerPrior != null && chance != null
                && c.answerPrior >= p.routineAnswerPrior && chance >= p.routineHumanAnswer;
    }

    // the testing criterion's measure, lower is a better main line resistance: easy to get
    // wrong, less a credit for being a move a human would play. without that credit a
    // move no human would play could take the main line just because its answer is obscure
    static double testingScore(CandidateFeatures c, ChoiceParams p) {
        return answerChance(c) - p.naturalnessWeight * humanPolicy(c);
    }
}
