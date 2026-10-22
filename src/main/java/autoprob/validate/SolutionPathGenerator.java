package autoprob.validate;

import java.awt.Point;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import autoprob.KataBrain;
import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.validate.ProblemIsolator.IsolatedProblem;

// generates the main correct solution line for an isolated problem.
// the solver plays katago's best move; the opponent plays the strongest resistance
// that still constitutes a real threat. the path ends when the opponent has no
// plausible move left that the solver must answer.
//
// each step is a decision: DecisionFeatureExtractor asks the engine for the candidates
// and their numbers, then ChoiceRule picks one. every decision is recorded so it can be
// reviewed, replayed as a test case, and re-chosen offline under other settings.
public class SolutionPathGenerator {
    private static final DecimalFormat df = new DecimalFormat("0.00");

    private final IsolatedProblem iso;
    private final DecisionFeatureExtractor extractor;
    private final ChoiceParams params;

    private final int visits;
    private final int visitsRoot;
    private final int maxMoves;
    private Point forcedFirstMove; // optional: use this as the first solver move

    public static class GenResult {
        public Node endNode; // last solver move, marked RIGHT
        public boolean endedNaturally = true;
        public List<String> notes = new ArrayList<>();
        public List<DecisionRecord> decisions = new ArrayList<>();
    }

    public SolutionPathGenerator(Properties props, KataBrain brain, IsolatedProblem iso) {
        this.iso = iso;
        this.params = ChoiceParams.fromProps(props);
        this.extractor = new DecisionFeatureExtractor(props, brain, iso, params);

        visits = Integer.parseInt(props.getProperty("redo.visits", "1000"));
        visitsRoot = Integer.parseInt(props.getProperty("redo.visits_root", "2000"));
        maxMoves = Integer.parseInt(props.getProperty("redo.max_moves", "20"));
    }

    // diagnostic: force the first solver move (e.g. the reference solution's first move)
    public void setForcedFirstMove(Point p) {
        forcedFirstMove = p;
    }

    public GenResult generateMainLine() throws Exception {
        GenResult result = new GenResult();
        Node cur = iso.problem;
        List<String> at = new ArrayList<>();
        int moveCount = 0;

        while (true) {
            // solver to move
            DecisionRecord solver = extractor.solver(cur, at, moveCount == 0 ? visitsRoot : visits);
            if (solver.error != null) {
                result.notes.add("analysis failed at move " + (moveCount + 1) + ", truncating");
                result.endedNaturally = false;
                break;
            }
            ChoiceRule.Choice choice = ChoiceRule.chooseSolver(solver);
            String desc;
            if (choice.isEnd()) {
                desc = choice.reason;
            } else {
                CandidateFeatures top = solver.candidate(choice.move);
                desc = "policy " + df.format(top.prior) + ", visits " + top.visits + ", score " + df.format(top.scoreLead);
            }
            if (moveCount == 0 && forcedFirstMove != null) {
                String forced = solver.toMove + " " + Intersection.toGTPloc(forcedFirstMove.x, forcedFirstMove.y, 19);
                if (!forced.equals(choice.move)) {
                    result.notes.add("forced first move " + forced.substring(2) + " over katago choice "
                            + (choice.isEnd() ? "pass" : choice.move.substring(2)));
                }
                choice = new ChoiceRule.Choice(forced, "forced first move");
                desc = "forced";
            }
            solver.chose = choice.move;
            solver.reason = choice.reason;
            result.decisions.add(solver);
            if (choice.isEnd()) {
                result.notes.add("solver wants to pass at move " + (moveCount + 1) + ", ending");
                break;
            }

            Point p = Intersection.gtp2point(choice.move.substring(2));
            Node solverNode = cur.addBasicMove(p.x, p.y);
            moveCount++;
            at.add(choice.move);
            System.out.println("move " + moveCount + " (solver): " + choice.move.substring(2) + " (" + desc + ")");
            result.endNode = solverNode;

            KataAnalysisResult karAfter = extractor.analyzeNear(solverNode, visits);
            if (karAfter.isError()) {
                result.notes.add("analysis failed after move " + moveCount + ", truncating");
                result.endedNaturally = false;
                break;
            }
            checkStakesHeld(karAfter, moveCount, result);
            checkScoreDrift(karAfter, moveCount, result);

            if (moveCount >= maxMoves) {
                result.notes.add("hit max moves (" + maxMoves + "), truncating");
                result.endedNaturally = false;
                break;
            }

            // ending check: does the opponent have any real threat left?
            DecisionRecord opponent = extractor.opponent(solverNode, at, karAfter);
            ChoiceRule.Choice response = ChoiceRule.chooseOpponent(opponent, params, iso.stakes);
            opponent.chose = response.move;
            opponent.reason = response.reason;
            result.decisions.add(opponent);
            if (response.isEnd()) {
                System.out.println("  " + response.reason + ": path ends after move " + moveCount);
                break;
            }
            System.out.println("  " + response.reason);

            p = Intersection.gtp2point(response.move.substring(2));
            Node oppNode = solverNode.addBasicMove(p.x, p.y);
            moveCount++;
            at.add(response.move);
            System.out.println("move " + moveCount + " (opponent): " + response.move.substring(2)
                    + " (" + DecisionFeatureExtractor.describe(opponent.candidate(response.move)) + ")");
            cur = oppNode;
        }

        if (result.endNode != null) {
            result.endNode.result = Intersection.RIGHT;
        }
        return result;
    }

    private boolean scoreDriftNoted = false;

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

    // warn if the stake stones are no longer settled the way the root solution expects
    private void checkStakesHeld(KataAnalysisResult kar, int moveCount, GenResult result) {
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
