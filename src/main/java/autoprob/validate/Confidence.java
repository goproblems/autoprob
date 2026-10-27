package autoprob.validate;

import java.util.ArrayList;
import java.util.List;

// how far to trust a suggestion, for the people who review it before it reaches the site: a
// band like "probably right (65-85%)" and the reasons for and against, so a reviewer sees what
// to check first. it is a heuristic, not a measured rate: it starts from a base and moves with
// each piece of evidence, and the band widens with each reason against. once reviews have
// judged enough suggestions (the case library), the weights can be fitted to them
public class Confidence {
    public final int low, high;          // percent
    public final String label;
    public final List<String> reasons;   // "+ " for, "- " against

    // what is known about a suggestion
    public static class Evidence {
        public boolean missing;          // a correct move the problem lacks; else a move it accepts that falls short
        public int kos;                  // kos on the line that shows the result, settled by giving one side every threat
        public int searched;             // answers the result held against only through the search's deviations
        public double margin = 1;        // how clearly the stones at stake end the solver's way (StatusJudge.margin)
        public boolean authorRejects;    // missing: the problem has the move as wrong; else: it answers the move
                                         // with the same refutation and still accepts it
        public boolean transposes;       // the move's line reaches a position on the author's correct lines
        public boolean authorPlaysElsewhere; // the author's correct lines play the same move at another position
        public boolean katagoFirst;      // katago's own first choice there
        public boolean authorAgrees;     // the author's accepted moves reach the best level too
        public boolean koSpot;           // the move takes a ko, or comes right after a ko capture
        public double distance;          // missing: how far the move is from the nearest stone of the problem
        public boolean outside;          // missing: open lines to the edge on three sides
        public List<String> isolation = new ArrayList<>(); // what the isolation had to adjust
    }

    private Confidence(int low, int high, List<String> reasons) {
        this.low = low;
        this.high = high;
        this.reasons = reasons;
        int mid = (low + high) / 2;
        this.label = mid >= 80 ? "likely right" : mid >= 60 ? "probably right" : mid >= 40 ? "uncertain" : "doubtful";
    }

    public static Confidence of(Evidence e) {
        List<String> reasons = new ArrayList<>();
        // telling the author a move is wrong is the bolder claim
        int score = e.missing ? 75 : 70;
        int against = 0;
        if (e.kos > 0) {
            score -= 15;
            against++;
            reasons.add("- its line goes through " + (e.kos == 1 ? "a ko" : e.kos + " kos")
                    + ", settled by giving one side every threat");
        }
        if (e.searched > 0) {
            score -= e.searched >= 3 ? 15 : 10;
            against++;
            reasons.add("- against " + e.searched + " of the opponent's answers the result came from the search's"
                    + " deviations, not from KataGo's own line");
        }
        // the deciding stone at stake is the weakest once the stone or two that may be lost
        // are set aside; under 0.7 it is not clearly settled (a guess, like every weight here)
        if (e.margin < 0.7) {
            score -= 10;
            against++;
            reasons.add("- the stones at stake end the solver's way only narrowly (ownership " + String.format("%.2f", e.margin) + ")");
        }
        if (e.koSpot) {
            score -= 5;
            against++;
            reasons.add("- it takes a ko, or comes right after a ko capture");
        }
        // a move far from every stone, or out in the open, that keeps the status usually
        // means the position was settled already
        if (e.distance > 2.5) {
            score -= 10;
            against++;
            reasons.add("- it is " + String.format("%.1f", e.distance) + " lines from the nearest stone, more like a tenuki");
        } else if (e.outside) {
            score -= 5;
            against++;
            reasons.add("- it is on the outside of the problem, open to the edge on three sides");
        }
        if (e.authorRejects) {
            score -= e.missing ? 15 : 10;
            against++;
            reasons.add(e.missing ? "- the problem has the move on a wrong line: the author looked at it and rejected it"
                    : "- the problem answers the move this way too and still accepts it");
        }
        int adjusted = Math.min(3, e.isolation.size());
        if (adjusted > 0) {
            score -= 5 * adjusted;
            against++;
            reasons.add("- the isolation had to be adjusted: " + String.join("; ", e.isolation.subList(0, adjusted)));
        }
        if (e.transposes) {
            score += 15;
            reasons.add("+ its line reaches a position on the author's correct lines");
        }
        if (e.authorPlaysElsewhere) {
            score += 5;
            reasons.add("+ the author plays the same move on a correct line elsewhere in the problem");
        }
        if (e.katagoFirst) {
            score += 5;
            reasons.add("+ KataGo's own first choice there");
        }
        if (e.authorAgrees) {
            score += 5;
            reasons.add("+ the author's accepted moves reach the same result here, so the isolation reads the problem as the author does");
        }
        score = Math.max(5, Math.min(95, score));
        int width = Math.min(25, 5 + 5 * against);
        int low = Math.max(1, round5(score - width));
        int high = Math.min(99, round5(score + width));
        return new Confidence(low, high, reasons);
    }

    private static int round5(int v) {
        return Math.round(v / 5f) * 5;
    }

    // "probably right (65-85%)"
    public String text() {
        return label + " (" + low + "-" + high + "%)";
    }

    // the band with its reasons, for a comment or a note: "probably right (65-85%). For it:
    // ... Against it: ..."
    public String explained() {
        List<String> forIt = new ArrayList<>(), against = new ArrayList<>();
        for (String r : reasons) (r.startsWith("+") ? forIt : against).add(r.substring(2));
        StringBuilder sb = new StringBuilder(text());
        if (!forIt.isEmpty()) sb.append(". For it: ").append(String.join("; ", forIt));
        if (!against.isEmpty()) sb.append(". Against it: ").append(String.join("; ", against));
        return sb.append('.').toString();
    }
}
