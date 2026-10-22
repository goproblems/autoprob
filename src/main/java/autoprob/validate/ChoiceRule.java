package autoprob.validate;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import autoprob.go.Intersection;

// picks a move (or END) from a decision's recorded candidates. pure: no engine calls,
// so the same function drives live generation, case replay, and offline tuning over
// stored candidates.
public class ChoiceRule {
    private static final DecimalFormat df = new DecimalFormat("0.00");

    public static class Choice {
        public final String move; // with color, or END
        public final String reason;

        public Choice(String move, String reason) {
            this.move = move;
            this.reason = reason;
        }

        public boolean isEnd() {
            return move.equals(DecisionRecord.END);
        }
    }

    // the solver plays katago's top move; a top move of pass ends the line
    public static Choice chooseSolver(DecisionRecord rec) {
        if (rec.candidates.isEmpty()) {
            return new Choice(DecisionRecord.END, "no solver moves");
        }
        CandidateFeatures top = rec.candidates.get(0);
        if (top.point().equals("pass")) {
            return new Choice(DecisionRecord.END, "solver wants to pass");
        }
        return new Choice(top.move, "katago's top move");
    }

    // the opponent response for the main line: the strongest resistance that is still a
    // real threat. a correct path continues only while the opponent has a move the solver
    // must answer; lesser tries belong in refutation branches. END when nothing threatens.
    public static Choice chooseOpponent(DecisionRecord rec, ChoiceParams p, List<Point> stakes) {
        List<CandidateFeatures> candidates = gather(rec, p);
        if (candidates.isEmpty()) {
            return new Choice(DecisionRecord.END, "no plausible opponent move");
        }

        if (p.extendMode.equals("primary")) {
            // only the opponent's best move may extend the main line
            CandidateFeatures c = candidates.get(0);
            int threat = countThreat(c, p, stakes);
            if (threat >= p.threatStones) {
                return new Choice(c.move, "primary response " + c.point() + ", threat " + threat);
            }
            return new Choice(DecisionRecord.END, "primary response " + c.point() + " only threatens "
                    + threat + " stones");
        }

        if (p.responseCriterion.equals("order")) {
            // the first candidate that still threatens extends the main line
            for (CandidateFeatures c : candidates) {
                int threat = countThreat(c, p, stakes);
                if (threat >= p.threatStones) {
                    return new Choice(c.move, "first threatening response " + c.point() + ", threat " + threat);
                }
            }
            return new Choice(DecisionRecord.END, "no opponent threat remains");
        }

        // choose among the sufficiently severe threats
        List<CandidateFeatures> threatening = new ArrayList<>();
        int maxThreat = 0;
        for (CandidateFeatures c : candidates) {
            int threat = countThreat(c, p, stakes);
            if (threat >= p.threatStones) {
                threatening.add(c);
                maxThreat = Math.max(maxThreat, threat);
            }
        }
        if (threatening.isEmpty()) {
            return new Choice(DecisionRecord.END, "no opponent threat remains");
        }
        double severeFloor = maxThreat * p.severeThreatRatio;
        CandidateFeatures best = null;
        for (CandidateFeatures c : threatening) {
            if (countThreat(c, p, stakes) < severeFloor) continue;
            if (best == null || better(c, best, p)) {
                best = c;
            }
        }
        return new Choice(best.move, "chose response " + best.point() + " (human " + df.format(humanPolicy(best))
                + ", threat " + countThreat(best, p, stakes) + ", human answer " + df.format(humanAnswer(best)) + ")");
    }

    // would a small change in the numbers flip this decision? engine noise between runs
    // moves human policies by a few hundredths and threat counts by a stone, so a choice
    // that flips under such nudges is a close call and may not reproduce on replay.
    public static boolean isFragile(DecisionRecord rec, ChoiceParams p, List<Point> stakes) {
        if (rec.role.equals(DecisionRecord.SOLVER)) {
            // katago orders by visits: a runner-up with comparable visits can take over
            if (rec.candidates.size() < 2) return false;
            Integer top = rec.candidates.get(0).visits, second = rec.candidates.get(1).visits;
            return top != null && second != null && second >= 0.6 * top;
        }
        String base = chooseOpponent(rec, p, stakes).move;
        for (int d : new int[]{-1, 1}) {
            ChoiceParams q = p.copy();
            q.threatStones += d;
            if (!chooseOpponent(rec, q, stakes).move.equals(base)) return true;
        }
        for (CandidateFeatures c : rec.candidates) {
            Double humanAnswer = c.humanAnswer, humanPolicy = c.humanPolicy;
            try {
                for (double d : new double[]{-0.05, 0.05}) {
                    c.humanAnswer = humanAnswer == null ? null : Math.max(0, humanAnswer + d);
                    if (!chooseOpponent(rec, p, stakes).move.equals(base)) return true;
                }
                c.humanAnswer = humanAnswer;
                for (double d : new double[]{-0.03, 0.03}) {
                    c.humanPolicy = humanPolicy == null ? null : Math.max(0, humanPolicy + d);
                    if (!chooseOpponent(rec, p, stakes).move.equals(base)) return true;
                }
            } finally {
                c.humanAnswer = humanAnswer;
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
            // correct refutation is hardest for a human to find. plain liberty
            // fills with automatic answers are auxiliary. compare in coarse bands
            // so near-equal difficulty falls through to human naturalness.
            int cBand = (int) (humanAnswer(c) / p.testingBand);
            int bestBand = (int) (humanAnswer(best) / p.testingBand);
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
        if (Boolean.TRUE.equals(c.error) || c.deltas == null) return 0;
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
}
