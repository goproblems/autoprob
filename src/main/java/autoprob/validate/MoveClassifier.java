package autoprob.validate;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

// the level a solver move reaches, the way goproblems ranks the results of a standard problem
// (article/bestpractices): clean, where the solver succeeds even with the opponent winning
// every ko; seki; ko, where it succeeds only by winning a ko itself; and failure. living, a
// seki is better than any ko, killing, any ko is better than a seki. success is what the
// stones at stake come to (StatusJudge), and each regime is played out by the RegimeWalker.
//
// katago's own search is not trusted to find the opponent's best answer: the answer that
// starts a ko is often one it does not look at, since on a board without threats it expects
// that ko lost. so every legal answer near the stones at stake is played and walked out too,
// and the worst counts. an answer that looks to bring the result down at the quick look is
// walked out again with a deeper search before it does. past the answer, each side may still
// deviate from katago's line where katago's view of kos misleads it (see deviate).
public class MoveClassifier {
    public enum Level { FAIL, KO, SEKI, CLEAN }

    // how many moves of katago's line, from the solver's first turn after the answer, either
    // side may deviate at, and how many deviations it tries at each
    private static final int WINDOW = 6;
    private static final int WIDTH = 3;
    // deviations within deviations
    private static final int DEPTH = 2;
    // the katago analyses one search may spend on deviations (ones already made are free), and
    // what has to be left of it to try one more: a deviation the other side has no budget left
    // to answer would decide the result just by coming last
    private static final int BUDGET = 16;
    private static final int RESERVE = 4;

    public static class Verdict {
        public String move;
        public Level level;
        public StatusJudge.Status clean;  // with the opponent winning every ko
        public StatusJudge.Status ko;     // with the solver winning every ko; null when not looked at
        public String refutation;         // the answer that brings the result down, if any
        public List<String> line;         // the walk that shows the result
        public boolean capped;            // short of the floor asked for: level is a stand-in, FAIL
        // how the level was reached, for the confidence in it: kos on the line that shows it,
        // the opponent's answers that only came out this way through the search's deviations
        // from katago's line, and the narrowest margin the stones at stake ended with
        public int kos;
        public int searched;
        public double margin = 1;
    }

    // the worst result the opponent can bring about after a move, under one regime, with how
    // many answers came to it only through the search's deviations and the narrowest margin
    // among the answers that came to it
    private record Worst(StatusJudge.Status status, String reply, List<String> line, int searched, double margin) {
    }

    // what a position comes to under one regime, the line that shows it, katago's line on from
    // where it settled, whether a deviation from katago's line brought it about, and the
    // margin of the stones at stake at its end
    private record Result(StatusJudge.Status status, List<String> line, List<String> pv, boolean deviated, double margin) {
        Result viaDeviation() {
            return new Result(status, line, pv, true, margin);
        }
    }

    private final ProblemIsolator.IsolatedProblem iso;
    private final RegimeWalker walker;
    private final int ownVisits;     // the walk after katago's own answer
    private final int replyVisits;   // the quick walk after each other answer
    private final int confirmVisits; // the walk again for an answer that does worse
    private final int replyDist;     // answers this close to a stone at stake or the move are tried
    private final boolean kills;

    public MoveClassifier(ProblemIsolator.IsolatedProblem iso, RegimeWalker walker,
                          int ownVisits, int replyVisits, int confirmVisits, int replyDist) {
        this.iso = iso;
        this.walker = walker;
        this.ownVisits = ownVisits;
        this.replyVisits = replyVisits;
        this.confirmVisits = confirmVisits;
        this.replyDist = replyDist;
        this.kills = StatusJudge.kills(iso);
    }

    // whether the problem is to kill, which ranks a ko above a seki
    public boolean kills() {
        return kills;
    }

    // how good a level is for this problem, higher better
    public int rank(Level l) {
        return switch (l) {
            case FAIL -> 0;
            case KO -> kills ? 2 : 1;
            case SEKI -> kills ? 1 : 2;
            case CLEAN -> 3;
        };
    }

    public Verdict classify(List<String> at, String move) throws Exception {
        return classify(at, move, null);
    }

    // the same, when only a level of at least floor matters (null for any): a move that falls
    // short of clean, or of seki in a living problem, can reach a ko at most, so when the floor
    // is above that the ko regime is not played out and the verdict is capped
    public Verdict classify(List<String> at, String move, Level floor) throws Exception {
        Verdict v = new Verdict();
        v.move = move;
        List<String> after = with(at, move);
        int opponent = iso.solverColor == Intersection.BLACK ? Intersection.WHITE : Intersection.BLACK;
        Worst clean = worst(after, opponent);
        v.clean = clean.status();
        v.refutation = clean.reply();
        v.line = clean.line();
        evidence(v, clean);
        if (clean.status() == StatusJudge.Status.SUCCESS) {
            v.level = Level.CLEAN;
            return v;
        }
        if (clean.status() == StatusJudge.Status.SEKI && !kills) {
            v.level = Level.SEKI;
            return v;
        }
        if (floor != null && rank(floor) > rank(Level.KO)) {
            v.level = Level.FAIL;
            v.capped = true;
            return v;
        }
        Worst ko = worst(after, iso.solverColor);
        v.ko = ko.status();
        // winning every ko can only help the solver, so a ko walk that comes to less than the
        // clean one has missed what the clean one found
        StatusJudge.Status best = ko.status().compareTo(clean.status()) >= 0 ? ko.status() : clean.status();
        if (best == StatusJudge.Status.SUCCESS) v.level = Level.KO;
        else if (best == StatusJudge.Status.SEKI) v.level = kills ? Level.SEKI : Level.KO;
        else v.level = Level.FAIL;
        // below a ko, what shows the result is the answer that holds even when the solver
        // wins every ko; a ko result is shown by the clean walk's answer, and reached in the
        // ko walk
        if (v.level == Level.FAIL || (v.level == Level.SEKI && ko.status() == StatusJudge.Status.SEKI)) {
            v.refutation = ko.reply() != null ? ko.reply() : clean.reply();
            v.line = ko.line();
        }
        evidence(v, ko);
        v.kos = Math.max(v.kos, walker.kos(ko.line()));
        return v;
    }

    // the level the position after the moves comes to: both regimes walked out from there,
    // with no sweep of every answer, for a generated line that has to show the level its move
    // was judged to reach where it ends
    public Level levelAfter(List<String> moves) throws Exception {
        int opponent = iso.solverColor == Intersection.BLACK ? Intersection.WHITE : Intersection.BLACK;
        StatusJudge.Status clean = search(moves, moves.size(), opponent, ownVisits, DEPTH, new int[]{BUDGET}).status();
        if (clean == StatusJudge.Status.SUCCESS) return Level.CLEAN;
        if (clean == StatusJudge.Status.SEKI && !kills) return Level.SEKI;
        StatusJudge.Status ko = search(moves, moves.size(), iso.solverColor, ownVisits, DEPTH, new int[]{BUDGET}).status();
        StatusJudge.Status best = ko.compareTo(clean) >= 0 ? ko : clean;
        if (best == StatusJudge.Status.SUCCESS) return Level.KO;
        if (best == StatusJudge.Status.SEKI) return kills ? Level.SEKI : Level.KO;
        return Level.FAIL;
    }

    private void evidence(Verdict v, Worst w) throws Exception {
        v.kos = walker.kos(v.line);
        v.searched = w.searched();
        v.margin = w.margin();
    }

    private Worst worst(List<String> after, int favored) throws Exception {
        int from = after.size() + 1; // the solver's first turn after the answer
        Result own = search(after, from, favored, ownVisits, DEPTH, new int[]{BUDGET});
        Map<String, Result> results = new LinkedHashMap<>(); // each answer's result, the deeper one when there is one
        results.put("", own);
        StatusJudge.Status status = own.status();
        String reply = null;
        List<String> line = own.line();
        if (status != StatusJudge.Status.FAIL) {
            // every other answer, quick, then the ones that do worse again with a deeper look
            record Try(String reply, StatusJudge.Status status) {
            }
            List<Try> worse = new ArrayList<>();
            for (String r : replies(after)) {
                Result quick = search(with(after, r), from, favored, replyVisits, 1, new int[]{BUDGET / 2});
                results.put(r, quick);
                if (quick.status().compareTo(status) < 0) worse.add(new Try(r, quick.status()));
            }
            worse.sort(Comparator.comparing(Try::status));
            for (Try t : worse) {
                Result deep = search(with(after, t.reply()), from, favored, confirmVisits, DEPTH, new int[]{BUDGET});
                results.put(t.reply(), deep);
                if (deep.status().compareTo(status) < 0) {
                    status = deep.status();
                    reply = t.reply();
                    line = deep.line();
                    if (status == StatusJudge.Status.FAIL) break;
                }
            }
        }
        int searched = 0;
        double margin = 1;
        for (Result r : results.values()) {
            if (r.status() != status) continue;
            if (r.deviated()) searched++;
            margin = Math.min(margin, r.margin());
        }
        return new Worst(status, reply, line, searched, margin);
    }

    // the position after the moves played out under the regime: katago's line, then each side's
    // deviations from it from index from on. single deviations are tried everywhere in the
    // window before deviations within them, so the budget is not spent deep in the first turn's
    // tries while a later turn has a plain answer: in gp-36582 white's S19, offering black the
    // ko, three turns in
    private Result search(List<String> moves, int from, int favored, int visits, int depth, int[] budget) throws Exception {
        int before = walker.fresh();
        RegimeWalker.Walk w = walker.walk(moves, favored, visits);
        budget[0] -= walker.fresh() - before;
        Result r = new Result(w.status(), w.line(), w.pv(), false, w.margin());
        for (int d = 1; d <= depth && budget[0] > 0; d++) r = deviate(r, from, favored, visits, d, budget);
        return r;
    }

    // katago plays both sides as if on a board without threats, where a ko goes to whoever
    // takes it first, and plays for points where the result here is life status, so its line
    // is not always either side's best under the regime. so each side may deviate at its turns
    // on the line: the solver, when the line falls short, with katago's next moves there; the
    // side that wins every ko with moves offering the other a ko to take, which katago passes
    // over, and while a ko is open with katago's next moves too, since katago, expecting that
    // ko lost for want of threats, gives up a fight the regime lets it win (gp-62080, where
    // white retakes the ko at C1 and katago then plays elsewhere for it, though G1 and E2 live);
    // and the opponent, with katago's next moves, at its turns before a ko it took and lost or
    // before the solver found a way out: its moves there were katago's guesses on the way to a
    // line it misjudged, deep in a line with few visits behind them.
    // each deviation is searched in turn with one less level of deviations, at the turns
    // turns() picks, and whenever a deviation changes the result the line is gone over again,
    // since the other side may now do better earlier; no position is tried twice
    private Result deviate(Result start, int from, int favored, int visits, int depth, int[] budget) throws Exception {
        String solver = IsolationRecord.colorLetter(iso.solverColor);
        boolean solverFavored = favored == iso.solverColor;
        Result cur = start;
        Set<String> tried = new HashSet<>();
        int rescued = -1; // where the solver last found a way out
        boolean changed = true;
        while (changed && budget[0] >= RESERVE) {
            changed = false;
            List<String> full = new ArrayList<>(cur.line());
            full.addAll(cur.pv());
            full = new ArrayList<>(full.subList(0, walker.legalPrefix(full)));
            Set<Integer> exchanges = walker.exchanges(full);
            int lostKo = solverFavored ? lastLostKo(full, exchanges, from, solver) : -1;
            for (int i : turns(full, exchanges, from)) {
                if (changed || budget[0] < RESERVE) break;
                String m = full.get(i);
                boolean solverTurn = m.startsWith(solver);
                if (solverTurn ? cur.status() == StatusJudge.Status.SUCCESS : cur.status() == StatusJudge.Status.FAIL) continue;
                List<String> prefix = new ArrayList<>(full.subList(0, i));
                if (!tried.add(String.join(",", prefix))) continue;
                Result best = cur;
                for (String a : tries(prefix, m, solverTurn, solverFavored, i < lostKo || i < rescued, visits, budget)) {
                    if (budget[0] < RESERVE) break;
                    Result r = search(with(prefix, a), i + 1, favored, visits, depth - 1, budget);
                    if (solverTurn ? r.status().compareTo(best.status()) > 0 : r.status().compareTo(best.status()) < 0) {
                        best = r;
                        if (solverTurn ? best.status() == StatusJudge.Status.SUCCESS : best.status() == StatusJudge.Status.FAIL) break;
                    }
                }
                if (best != cur) {
                    cur = best.viaDeviation();
                    changed = true;
                    if (solverTurn) rescued = i;
                }
            }
        }
        return cur;
    }

    // the turns of a line worth deviating at: its first moves from index from, where the
    // choices that decide a problem are. the walk's own exchanges are no one's choice; a move
    // katago plays away from the problem is a turn passed up there, on the walk's line as on
    // from where it settled: before a ko on its line, katago may play elsewhere because it
    // expects to win the ko, which the regime then takes away. (adding the turns before a ko a
    // side lost, which can be further on, was tried: on a fixed budget it spent what the first
    // turns needed, and gp-32711's Q19 lost its way out of the ko at the first turn)
    static List<Integer> turns(List<String> full, Set<Integer> exchanges, int from) {
        List<Integer> out = new ArrayList<>();
        for (int i = from; i < full.size() && out.size() < WINDOW; i++) {
            if (exchanges.contains(i)) continue;
            out.add(i);
        }
        return out;
    }

    // the deviations worth trying at a turn whose line move is m, the ko offers of the side that
    // wins every ko first, since those are what katago passes over. reconsider: the opponent's
    // turn comes before a ko it lost or a way out the solver found
    private Set<String> tries(List<String> prefix, String m, boolean solverTurn, boolean solverFavored, boolean reconsider,
                              int visits, int[] budget) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        boolean favoredTurn = solverTurn == solverFavored;
        if (favoredTurn) {
            List<Point> near = new ArrayList<>(iso.stakes);
            if (!prefix.isEmpty()) near.add(Intersection.gtp2point(prefix.get(prefix.size() - 1).substring(2)));
            List<String> offers = walker.koOffers(prefix, near, replyDist);
            out.addAll(offers.subList(0, Math.min(WIDTH, offers.size())));
        }
        if (solverTurn || reconsider || (favoredTurn && walker.koOpen(prefix))) {
            int favored = solverFavored ? iso.solverColor
                    : iso.solverColor == Intersection.BLACK ? Intersection.WHITE : Intersection.BLACK;
            out.addAll(katagoMoves(prefix, visits, favored, budget));
        }
        out.remove(m);
        return out;
    }

    // where the opponent last took a ko and lost it: the solver's exchange follows its move
    static int lastLostKo(List<String> full, Set<Integer> exchanges, int from, String solver) {
        int at = -1;
        for (int i = from; i + 1 < full.size(); i++) {
            if (!full.get(i).startsWith(solver) && !exchanges.contains(i)
                    && full.get(i + 1).startsWith(solver) && exchanges.contains(i + 1)) at = i;
        }
        return at;
    }

    // katago's first moves in the problem area after the moves, for the side to play there,
    // with favored winning every ko
    private List<String> katagoMoves(List<String> moves, int visits, int favored, int[] budget) throws Exception {
        int before = walker.fresh();
        KataAnalysisResult kar = walker.analyze(moves, visits, favored);
        budget[0] -= walker.fresh() - before;
        List<String> out = new ArrayList<>();
        if (kar.isError() || kar.moveInfos == null) return out;
        String color = moves.isEmpty() ? IsolationRecord.colorLetter(iso.solverColor)
                : moves.get(moves.size() - 1).startsWith("B") ? "W" : "B";
        for (MoveInfo mi : kar.moveInfos) {
            if (out.size() >= WIDTH) break;
            if (mi.move.equalsIgnoreCase("pass")) continue;
            String a = color + " " + mi.move;
            if (walker.inZone(a)) out.add(a);
        }
        return out;
    }

    // the opponent's answers worth trying: every legal point within replyDist of a stone at
    // stake or of the solver's move, outside the fill
    List<String> replies(List<String> after) throws Exception {
        List<Point> near = new ArrayList<>(iso.stakes);
        String last = after.get(after.size() - 1);
        near.add(Intersection.gtp2point(last.substring(2)));
        String color = last.startsWith("B") ? "W" : "B";
        Node first = null;
        try {
            Node node = iso.problem;
            for (String m : after) {
                Point p = Intersection.gtp2point(m.substring(2));
                node = node.addBasicMove(p.x, p.y);
                if (first == null) first = node;
            }
            List<String> out = new ArrayList<>();
            for (int x = 0; x < 19; x++)
                for (int y = 0; y < 19; y++) {
                    if (node.board.board[x][y].stone != Intersection.EMPTY || iso.isFilled(x, y)) continue;
                    if (iso.threats.contains(new Point(x, y))) continue;
                    boolean close = false;
                    for (Point s : near) {
                        if (Math.max(Math.abs(s.x - x), Math.abs(s.y - y)) <= replyDist) close = true;
                    }
                    if (close && node.legalMove(new Point(x, y))) out.add(color + " " + Intersection.toGTPloc(x, y, 19));
                }
            return out;
        } finally {
            if (first != null) iso.problem.removeChildNode(first);
        }
    }

    private static List<String> with(List<String> moves, String move) {
        List<String> out = new ArrayList<>(moves);
        out.add(move);
        return out;
    }
}
