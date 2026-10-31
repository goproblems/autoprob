package autoprob.validate;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.function.Function;

import autoprob.KataBrain;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.validate.ProblemIsolator.IsolatedProblem;

// generates the correct part of the solution tree for an isolated problem.
//
// at a solver turn every move that works goes in the tree: in a problem, a move that
// solves it but is missing gets a correct answer marked wrong. at an opponent turn the
// strongest resistance that is still a real threat goes in (more with
// redo.opponent_branches); when the opponent has no move the solver must answer, that
// branch ends. where a line runs on a problem's own tree (setOwnAnswers), the opponent
// plays the problem's own answers instead, each of them and nothing else.
//
// each turn is a decision: DecisionFeatureExtractor asks the engine for the candidates
// and their numbers, then ChoiceRule picks the moves. every decision is recorded so it
// can be reviewed, replayed as a test case, and re-chosen offline under other settings.
// the main line follows the first choice at every turn.
//
// turns are expanded main line first, then the other branches shallowest first, so when
// the tree hits redo.max_tree_decisions the cut falls on the deep ends of side branches,
// never on an alternative first move. a limit only keeps a branch from going deeper: every
// move that works at a turn already decided stays in the tree, and the lines a limit stops
// are listed (GenResult.cut) so whoever reviews the tree can see where it was cut short.
public class SolutionPathGenerator {
    private static final DecimalFormat df = new DecimalFormat("0.00");

    private final IsolatedProblem iso;
    private final DecisionFeatureExtractor extractor;
    private final ChoiceParams params;

    private final int visits;
    private final int visitsRoot;
    private final int maxMoves;
    private final int maxDecisions;
    private Point forcedFirstMove; // optional: use this as the first solver move
    // optional: the problem's own answers after the moves from here, the ones the site's
    // computer plays; none where the moves leave the problem's tree
    private Function<List<String>, Collection<String>> ownAnswers = at -> List.of();

    private GenResult result;
    private boolean scoreDriftNoted = false;
    // decisions by position, so a transposition reuses the engine work
    private final Map<String, DecisionRecord> decided = new HashMap<>();

    // a turn waiting to be expanded: main line first, then shallowest first, then in order
    private record Turn(Node node, List<String> at, boolean main, boolean solverToMove, long seq) {}
    private final PriorityQueue<Turn> pending = new PriorityQueue<>(Comparator
            .comparing((Turn t) -> !t.main())
            .thenComparingInt(t -> t.at().size())
            .thenComparingLong(Turn::seq));
    private long turnCount = 0;

    public static class GenResult {
        public Node endNode; // end of the main line, marked RIGHT like every leaf
        public boolean endedNaturally = true; // the main line ended by the threat rule
        public int truncated; // branches cut short by the move or decision limit or a failed analysis
        public List<Cut> cut = new ArrayList<>(); // the lines those end, each with why
        public List<String> notes = new ArrayList<>();
        public List<DecisionRecord> decisions = new ArrayList<>();
    }

    // a line that stops at a limit or a failed analysis rather than where the opponent has no
    // threat left: the moves to its last solver move, and why it stops there
    public record Cut(List<String> at, String why) {}

    public SolutionPathGenerator(Properties props, KataBrain brain, IsolatedProblem iso) {
        this.iso = iso;
        this.params = ChoiceParams.fromProps(props);
        this.extractor = new DecisionFeatureExtractor(props, brain, iso, params);

        visits = Integer.parseInt(props.getProperty("redo.visits", "1000"));
        visitsRoot = Integer.parseInt(props.getProperty("redo.visits_root", "2000"));
        maxMoves = Integer.parseInt(props.getProperty("redo.max_moves", "20"));
        maxDecisions = Integer.parseInt(props.getProperty("redo.max_tree_decisions", "60"));
    }

    // diagnostic: force the first solver move (e.g. the reference solution's first move)
    public void setForcedFirstMove(Point p) {
        forcedFirstMove = p;
    }

    // the problem's own tree, as its answers after the moves from here: where a line runs on
    // it, the site's computer answers with the problem's moves, so the line takes those
    public void setOwnAnswers(Function<List<String>, Collection<String>> answers) {
        ownAnswers = answers;
    }

    // builds the tree under iso.problem; every leaf is marked RIGHT
    public GenResult generate() throws Exception {
        result = new GenResult();
        enqueue(iso.problem, new ArrayList<>(), true, true);
        while (!pending.isEmpty()) {
            Turn t = pending.poll();
            if (t.solverToMove()) solverTurn(t.node(), t.at(), t.main());
            else opponentTurn(t.node(), t.at(), t.main());
        }
        if (result.truncated > 0) {
            result.notes.add(result.truncated + " branch" + (result.truncated == 1 ? "" : "es") + " cut short");
        }
        return result;
    }

    private void enqueue(Node node, List<String> at, boolean main, boolean solverToMove) {
        pending.add(new Turn(node, at, main, solverToMove, turnCount++));
    }

    // node: the solver is to move here
    private void solverTurn(Node node, List<String> at, boolean main) throws Exception {
        int moveNumber = at.size() + 1;
        if (!at.isEmpty() && result.decisions.size() >= maxDecisions && !answersOwn(at)) {
            cut(node, at, main, "reached the tree limit of " + maxDecisions + " decisions");
            return;
        }
        DecisionRecord solver = decide(node, at, true, null);
        if (solver.error != null) {
            cut(node, at, main, "analysis failed at move " + moveNumber);
            return;
        }
        ChoiceRule.Choice choice = ChoiceRule.chooseSolver(solver, params, iso.stakes);
        if (at.isEmpty() && forcedFirstMove != null) {
            String forced = solver.toMove + " " + Intersection.toGTPloc(forcedFirstMove.x, forcedFirstMove.y, 19);
            if (!forced.equals(choice.move)) {
                result.notes.add("forced first move " + forced.substring(2) + " over katago choice "
                        + (choice.isEnd() ? "pass" : choice.move.substring(2)));
            }
            choice = new ChoiceRule.Choice(forced, "forced first move");
        }
        record(solver, choice);
        if (choice.isEnd()) {
            // the opponent's last move needs no answer: the line ends before it
            result.notes.add("solver wants to pass at move " + moveNumber + ", ending");
            if (!at.isEmpty()) {
                Node mom = node.mom;
                mom.removeChildNode(node);
                if (mom.babies.isEmpty()) mom.result = Intersection.RIGHT;
            }
            return;
        }
        if (!choice.also.isEmpty()) {
            System.out.println("  " + choice.reason + ": " + String.join(", ",
                    choice.moves().stream().map(m -> m.substring(2)).toList()));
        }

        List<String> moves = choice.moves();
        for (int i = 0; i < moves.size(); i++) {
            String move = moves.get(i);
            Point p = Intersection.gtp2point(move.substring(2));
            Node child = node.addBasicMove(p.x, p.y);
            CandidateFeatures c = solver.candidate(move);
            String desc = c == null ? "forced" : "policy " + df.format(c.prior == null ? 0 : c.prior)
                    + ", visits " + c.visits + (c.afterLead != null ? ", lead after " + df.format(c.afterLead)
                    : c.scoreLead != null ? ", score " + df.format(c.scoreLead) : "");
            System.out.println("move " + moveNumber + " (solver" + (i > 0 ? ", alternative" : "") + "): "
                    + move.substring(2) + " (" + desc + ")" + (main ? "" : " in " + DecisionRecord.key(at)));
            List<String> next = new ArrayList<>(at);
            next.add(move);
            boolean childMain = main && i == 0;
            if (childMain) result.endNode = child;
            enqueue(child, next, childMain, false);
        }
    }

    // node: right after a solver move, the opponent is to move
    private void opponentTurn(Node node, List<String> at, boolean main) throws Exception {
        int moveCount = at.size();
        // on the problem's own tree the site's computer answers with the problem's moves, so the
        // line goes on through each of them and adds none of its own beside them: a correct line
        // the computer never takes shows nothing (adam's review of gp-62134: after B B3, W B2,
        // B D1, the line took W C4, the try a human gets wrong most, beside the problem's C3,
        // the stronger one)
        String color = IsolationRecord.colorLetter(node.getToMove());
        List<String> own = new ArrayList<>();
        for (String m : ownAnswers.apply(at)) {
            if (m.startsWith(color + " ") && node.legalMove(Intersection.gtp2point(m.substring(2)))) own.add(m);
        }
        if (!own.isEmpty()) {
            for (int i = 0; i < own.size(); i++) {
                String move = own.get(i);
                Point p = Intersection.gtp2point(move.substring(2));
                Node child = node.addBasicMove(p.x, p.y);
                System.out.println("move " + (moveCount + 1) + " (opponent, the problem's" + (i > 0 ? ", alternative" : "") + "): "
                        + move.substring(2) + (main ? "" : " in " + DecisionRecord.key(at)));
                List<String> next = new ArrayList<>(at);
                next.add(move);
                enqueue(child, next, main && i == 0, true);
            }
            return;
        }
        if (moveCount >= maxMoves) {
            end(node, at, main, "hit max moves (" + maxMoves + ")");
            return;
        }
        if (result.decisions.size() >= maxDecisions) {
            end(node, at, main, "reached the tree limit of " + maxDecisions + " decisions");
            return;
        }
        KataAnalysisResult karAfter = extractor.after(node);
        if (karAfter.isError()) {
            end(node, at, main, "analysis failed after move " + moveCount);
            return;
        }
        if (main) {
            checkStakesHeld(karAfter, moveCount, result);
            checkScoreDrift(karAfter, moveCount, result);
        }

        // ending check: does the opponent have any real threat left?
        DecisionRecord opponent = decide(node, at, false, karAfter);
        if (opponent.error != null) {
            end(node, at, main, "analysis failed after move " + moveCount);
            return;
        }
        ChoiceRule.Choice response = ChoiceRule.chooseOpponent(opponent, params, iso.stakes);
        record(opponent, response);
        if (response.isEnd()) {
            System.out.println("  " + response.reason + ": path ends after move " + moveCount);
            node.result = Intersection.RIGHT;
            return;
        }
        System.out.println("  " + response.reason);

        List<String> moves = response.moves();
        for (int i = 0; i < moves.size(); i++) {
            String move = moves.get(i);
            Point p = Intersection.gtp2point(move.substring(2));
            Node child = node.addBasicMove(p.x, p.y);
            System.out.println("move " + (moveCount + 1) + " (opponent" + (i > 0 ? ", alternative" : "") + "): "
                    + move.substring(2) + " (" + DecisionFeatureExtractor.describe(opponent.candidate(move)) + ")");
            List<String> next = new ArrayList<>(at);
            next.add(move);
            enqueue(child, next, main && i == 0, true);
        }
    }

    // whether the last move is one of the problem's own answers: the site's computer plays it,
    // so the solver answers it whatever the limits, or the line would end on the problem's wrong
    // line, which it cannot be added to
    private boolean answersOwn(List<String> at) {
        return ownAnswers.apply(at.subList(0, at.size() - 1)).contains(at.get(at.size() - 1));
    }

    // the decision at this position, reusing one made at the same position reached by
    // another move order
    private DecisionRecord decide(Node node, List<String> at, boolean solverToMove, KataAnalysisResult karAfter)
            throws Exception {
        String key = DecisionFeatureExtractor.positionKey(node);
        DecisionRecord known = decided.get(key);
        if (known != null) {
            System.out.println("  " + DecisionRecord.key(at) + " transposes to " + known.key());
            return known.copyAt(at);
        }
        DecisionRecord rec = solverToMove
                ? extractor.solver(node, at, at.isEmpty() ? visitsRoot : visits)
                : extractor.opponent(node, at, karAfter);
        if (rec.error == null) decided.put(key, rec);
        return rec;
    }

    private void record(DecisionRecord rec, ChoiceRule.Choice choice) {
        rec.chose = choice.move;
        rec.alternatives = new ArrayList<>(choice.also);
        rec.reason = choice.reason;
        result.decisions.add(rec);
    }

    // a branch stops after a solver move: that move is the end of a correct line
    private void end(Node node, List<String> at, boolean main, String why) {
        node.result = Intersection.RIGHT;
        stopped(at, main, why);
    }

    // a branch stops at a solver turn (node follows an opponent move): drop that move, and
    // the solver move before it becomes the end of the line unless other branches remain
    private void cut(Node node, List<String> at, boolean main, String why) {
        List<String> end = null;
        if (node.mom != null) {
            Node mom = node.mom;
            mom.removeChildNode(node);
            if (mom.babies.isEmpty()) {
                mom.result = Intersection.RIGHT;
                if (!at.isEmpty()) end = at.subList(0, at.size() - 1);
            }
        }
        stopped(end, main, why);
    }

    // end: the line the stop ends, or null when other branches go on past it
    private void stopped(List<String> end, boolean main, String why) {
        result.truncated++;
        if (end != null) result.cut.add(new Cut(List.copyOf(end), why));
        String note = why + ", truncating";
        if (main) {
            result.endedNaturally = false;
            result.notes.add(note);
        }
        System.out.println("  " + note);
    }

    // a big gap between the score and the komi balance target means the isolation
    // baseline missed part of the solution -- the game is no longer close
    private void checkScoreDrift(KataAnalysisResult kar, int moveCount, GenResult result) {
        if (scoreDriftNoted) return;
        double drift = kar.blackScore() - iso.targetLead;
        if (Math.abs(drift) > 15) {
            String note = "WARNING: score drifted " + df.format(drift) + " from the komi target after move "
                    + moveCount + " -- komi baseline likely missed the solution";
            System.out.println(note);
            result.notes.add(note);
            scoreDriftNoted = true;
        }
    }

    // warn if the stake stones are no longer settled the way the root solution expects.
    // an isolation rebuilt from a record has no solved state to compare with
    private void checkStakesHeld(KataAnalysisResult kar, int moveCount, GenResult result) {
        if (iso.solvedKar == null) return;
        int flipped = 0;
        for (Point s : iso.stakes) {
            double rootOwn = iso.solvedKar.ownership.get(s.x + s.y * 19);
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
}
