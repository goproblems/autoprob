package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.katastruct.KataAnalysisResult;

class StatusJudgeTest {
    // gp-32711: black lives in the top right corner inside white's wall
    static final String[] BLACK = {"P17", "Q17", "R18", "R17", "R16", "S15"};
    static final String[] WHITE = {"O17", "O16", "P18", "P16", "Q18", "Q16", "R15", "R14", "S14"};

    // the problem on the fortress board, black to live, its stones at stake
    static ProblemIsolator.IsolatedProblem problem() {
        ProblemIsolator.IsolatedProblem iso = new ProblemIsolator.IsolatedProblem();
        iso.problem = new Node(null);
        for (String s : BLACK) put(iso, s, Intersection.BLACK);
        for (String s : WHITE) put(iso, s, Intersection.WHITE);
        new BalancedFortress().build(iso.problem.board, 4, iso.filledStones);
        iso.solverColor = Intersection.BLACK;
        iso.problem.defaultToMoveColor = Intersection.BLACK;
        for (String s : BLACK) iso.stakes.add(Intersection.gtp2point(s));
        return iso;
    }

    private static void put(ProblemIsolator.IsolatedProblem iso, String gtp, int color) {
        Point p = Intersection.gtp2point(gtp);
        iso.problem.board.board[p.x][p.y].stone = color;
    }

    // black owns the corner (columns P to T down to row 17, and R to T down to row 15), white
    // everything else; the fill stones belong to their color. then the given points get the
    // given ownership
    private static KataAnalysisResult ownership(ProblemIsolator.IsolatedProblem iso, Object... overrides) {
        List<Double> own = new ArrayList<>();
        for (int i = 0; i < 361; i++) own.add(0.0);
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                double o;
                if (iso.isFilled(x, y)) o = iso.filledStones.board[x][y].stone == Intersection.BLACK ? 1 : -1;
                else o = (x >= 14 && y <= 2) || (x >= 16 && y <= 4) ? 1 : -1;
                own.set(x + y * 19, o);
            }
        for (int i = 0; i < overrides.length; i += 2) {
            Point p = Intersection.gtp2point((String) overrides[i]);
            own.set(p.x + p.y * 19, (Double) overrides[i + 1]);
        }
        KataAnalysisResult kar = new KataAnalysisResult();
        kar.ownership = own;
        return kar;
    }

    @Test
    void theCornerLivingIsSuccess() {
        ProblemIsolator.IsolatedProblem iso = problem();
        StatusJudge judge = new StatusJudge(iso, 2);
        // white's wall and the open ground around it reach the fill: not a pocket
        assertEquals(StatusJudge.Status.SUCCESS, judge.status(iso.problem, ownership(iso)));
    }

    @Test
    void aStoneOrTwoMayBeLostButNotMore() {
        ProblemIsolator.IsolatedProblem iso = problem();
        StatusJudge judge = new StatusJudge(iso, 2);
        assertEquals(StatusJudge.Status.SUCCESS, judge.status(iso.problem, ownership(iso, "S15", -1.0)));
        assertEquals(StatusJudge.Status.FAIL, judge.status(iso.problem, ownership(iso, "P17", -1.0, "Q17", -1.0, "R17", -1.0)));
        // a stone whose fate is open is not alive yet
        assertEquals(StatusJudge.Status.FAIL, judge.status(iso.problem, ownership(iso, "P17", 0.2, "Q17", 0.1, "R17", 0.0)));
        assertEquals(List.of("P17", "Q17", "R17"), judge.missed(ownership(iso, "P17", -1.0, "Q17", -1.0, "R17", -1.0)));
    }

    @Test
    void anEnclosedPocketTheOpponentKeepsIsASeki() {
        ProblemIsolator.IsolatedProblem iso = problem();
        StatusJudge judge = new StatusJudge(iso, 2);
        // white keeps S19 alive inside black's corner, sharing the liberty T19
        put(iso, "S19", Intersection.WHITE);
        assertEquals(StatusJudge.Status.SEKI, judge.status(iso.problem, ownership(iso, "S19", -1.0, "T19", 0.0)));
        assertEquals(List.of("S19", "T19"), judge.pocket(iso.problem, ownership(iso, "S19", -1.0, "T19", 0.0)));
        // a neutral point alone is not the opponent's: no pocket
        assertEquals(StatusJudge.Status.SUCCESS, judge.status(iso.problem, ownership(iso, "S19", 1.0, "T19", 0.0)));
    }

    @Test
    void aGroupOfTheOpponentsOwnIsNoPocket() {
        ProblemIsolator.IsolatedProblem iso = problem();
        // white's S19 and T19 lived in the solved position already: a group of its own there,
        // whatever black's stones come to
        put(iso, "S19", Intersection.WHITE);
        iso.solvedKar = ownership(iso, "S19", -1.0, "T19", -1.0);
        StatusJudge judge = new StatusJudge(iso, 2);
        assertEquals(StatusJudge.Status.SUCCESS, judge.status(iso.problem, ownership(iso, "S19", -1.0, "T19", -1.0)));
        // but one that comes alive where black's area was is a seki
        put(iso, "R19", Intersection.WHITE);
        assertEquals(StatusJudge.Status.SEKI,
                judge.status(iso.problem, ownership(iso, "S19", -1.0, "T19", -1.0, "R19", -1.0, "Q19", 0.0)));
    }

    @Test
    void theGoalGoesByWhoseStonesAreAtStake() {
        ProblemIsolator.IsolatedProblem iso = problem();
        assertFalse(StatusJudge.kills(iso), "black's own stones are at stake: black lives");
        iso.solverColor = Intersection.WHITE;
        assertTrue(StatusJudge.kills(iso), "the same stones with white to play: white kills");
    }

    @Test
    void theProblemAreaAndTheNeutralFillPoints() {
        ProblemIsolator.IsolatedProblem iso = problem();
        boolean[][] zone = RegimeWalker.zone(iso);
        for (String s : BLACK) assertTrue(zone[Intersection.gtp2point(s).x][Intersection.gtp2point(s).y]);
        Point open = Intersection.gtp2point("T19");
        assertTrue(zone[open.x][open.y]);
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) {
                if (iso.isFilled(x, y)) assertFalse(zone[x][y], "a fill stone is not in the problem area");
            }
        for (int color : new int[]{Intersection.BLACK, Intersection.WHITE}) {
            List<Point> neutral = RegimeWalker.neutralPoints(iso, zone, color);
            assertTrue(neutral.size() >= 4, "enough neutral points for exchanges");
            for (Point p : neutral) {
                assertFalse(zone[p.x][p.y]);
                assertEquals(Intersection.EMPTY, iso.problem.board.board[p.x][p.y].stone);
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int x = p.x + d[0], y = p.y + d[1];
                    if (x < 0 || y < 0 || x >= 19 || y >= 19) continue;
                    assertEquals(color, iso.problem.board.board[x][y].stone, "every neighbor is its own fill");
                }
            }
        }
    }
}
