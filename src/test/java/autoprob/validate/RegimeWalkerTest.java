package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.parse.Parser;
import autoprob.go.parse.SmallBoard;

class RegimeWalkerTest {
    // gp-62169, a 13x13 problem in the lower left corner as the finder plays it: black kills.
    // after B5 W B4 B A6 white's group is dead as it stands: white can retake the ko at B6 but
    // never fill it, since filling at B5 leaves the group one liberty
    static final String[] BLACK = {"A3", "B2", "C2", "D2", "E3", "F3", "F4", "F5", "E6", "D6", "C6", "F7", "B7", "A7"};
    static final String[] WHITE = {"A5", "A4", "B6", "B3", "C3", "C4", "C5", "D5", "E5", "E4", "D3"};

    // the problem on the fortress board the isolator builds for it (redo.gap 4): its walls
    // close the problem area at row 12 and column L, the fill from row 15 up is split at
    // column K, and the eyes of each side's own fill farthest from the problem are T18
    // (white's) and B19 (black's)
    static ProblemIsolator.IsolatedProblem problem() {
        ProblemIsolator.IsolatedProblem iso = new ProblemIsolator.IsolatedProblem();
        iso.problem = new Node(null);
        for (String s : BLACK) put(iso, s, Intersection.BLACK);
        for (String s : WHITE) put(iso, s, Intersection.WHITE);
        new BalancedFortress().build(iso.problem.board, 4, iso.filledStones);
        iso.solverColor = Intersection.BLACK;
        iso.problem.defaultToMoveColor = Intersection.BLACK;
        for (String s : WHITE) iso.stakes.add(Intersection.gtp2point(s));
        return iso;
    }

    // a problem from sample_problems on the same fortress board
    static ProblemIsolator.IsolatedProblem fromFile(String problem) throws Exception {
        Node root = new Parser().parse(SmallBoard.toNineteen(Files.readString(Path.of("sample_problems", problem + ".sgf"))).sgf());
        ProblemIsolator.IsolatedProblem iso = new ProblemIsolator.IsolatedProblem();
        iso.problem = new Node(null);
        for (int x = 0; x < 19; x++)
            for (int y = 0; y < 19; y++) iso.problem.board.board[x][y].stone = root.board.board[x][y].stone;
        iso.problem.defaultToMoveColor = root.getToMove();
        new BalancedFortress().build(iso.problem.board, 4, iso.filledStones);
        return iso;
    }

    private static void put(ProblemIsolator.IsolatedProblem iso, String gtp, int color) {
        Point p = Intersection.gtp2point(gtp);
        iso.problem.board.board[p.x][p.y].stone = color;
    }

    private static List<String> moves(String s) {
        return Arrays.stream(s.split(",")).map(String::trim).toList();
    }

    @Test
    void katagosMovesElsewhereBeforeAKoAreTurnsNotExchanges() throws Exception {
        RegimeWalker walker = new RegimeWalker(problem(), null, null);
        // katago's line, without threats: black plays elsewhere twice (K14, K15, outside the
        // problem area) while white pushes at C7 and B8, then takes the ko at B5, which wins it
        // the corner on that board. with white winning every ko, the walker gives white an
        // exchange and the retake
        List<String> line = moves("B B5,W B4,B A6,W K18,B K14,W C7,B K15,W B8,B B5,W T18,B B19,W B6");
        Set<Integer> exchanges = walker.exchanges(line);
        assertEquals(Set.of(9, 10), exchanges);
        assertEquals(1, walker.kos(line));
        // so black may deviate where it played elsewhere, answering the pushes
        assertEquals(List.of(2, 3, 4, 5, 6, 7), MoveClassifier.turns(line, exchanges, 2));
    }

    @Test
    void movesElsewhereAfterAKoTakeAreNoExchangeEither() throws Exception {
        RegimeWalker walker = new RegimeWalker(problem(), null, null);
        // after black takes the ko, katago's white has no threat and plays elsewhere, and so
        // does black: neither move is in an eye of its own fill, so the walker gave no threat
        List<String> line = moves("B B5,W B4,B A6,W K18,B B5,W K14,B K15,W B6");
        assertEquals(Set.of(), walker.exchanges(line));
        assertEquals(0, walker.kos(line));
    }

    @Test
    void aKoWhiteCanRetakeButNeverFillIsDead() throws Exception {
        ProblemIsolator.IsolatedProblem iso = problem();
        RegimeWalker walker = new RegimeWalker(iso, null, null);
        // white to play: black could take at B5, white could take back at B6, but filling B5
        // would leave white's group the one liberty at D4
        Node node = Positions.play(iso, moves("B B5,W B4,B A6"));
        assertEquals(Set.of("B5"), walker.deadKos(node, Intersection.WHITE));
        // with black winning every ko, white has no ko to take there
        assertEquals(Set.of(), walker.deadKos(node, Intersection.BLACK));
    }

    @Test
    void aKoIsRealOnceTakingItBackCapturesTheStonesAroundIt() throws Exception {
        ProblemIsolator.IsolatedProblem iso = problem();
        RegimeWalker walker = new RegimeWalker(iso, null, null);
        // white's pushes at C7 and B8 leave A6, A7 and B7 a single liberty while black plays
        // elsewhere: taking at B5 gains them B6, and white's retake there leaves them in atari
        Node node = Positions.play(iso, moves("B B5,W B4,B A6,W C7,B K14,W B8"));
        assertEquals(Set.of(), walker.deadKos(node, Intersection.WHITE));
        assertFalse(RegimeWalker.neverEnds(node, Intersection.gtp2point("B5")));
    }

    @Test
    void aKoWhosePointCanCaptureMoreLaterIsNotBarred() throws Exception {
        // gp-32711 after B O18 W N18 B T15 W T17 B T18 W S18 B S17 W T19 B Q19 W O19 B T16:
        // black can take T17 at T18 and white can never fill there, but black can also fill S19,
        // and then T18 captures T19 and S18 too, so barring T18 would bar that capture
        ProblemIsolator.IsolatedProblem iso = fromFile("gp-32711");
        RegimeWalker walker = new RegimeWalker(iso, null, null);
        Node node = Positions.play(iso, moves("B O18,W N18,B T15,W T17,B T18,W S18,B S17,W T19,B Q19,W O19,B T16"));
        Node pass = node.addBasicMove(19, 19); // black to play
        Point t18 = Intersection.gtp2point("T18");
        assertTrue(RegimeWalker.neverEnds(pass, t18));
        assertFalse(RegimeWalker.onlyAKo(pass, t18));
        assertEquals(Set.of(), walker.deadKos(node, Intersection.WHITE));
    }

    @Test
    void aKoTheFavoredSideCanFillIsNotDead() throws Exception {
        ProblemIsolator.IsolatedProblem iso = new ProblemIsolator.IsolatedProblem();
        iso.problem = new Node(null);
        iso.problem.defaultToMoveColor = Intersection.BLACK;
        // black takes at E4; white takes back at D4 and fills E4, its stones keeping five liberties
        for (String s : new String[]{"D5", "C4", "D3"}) put(iso, s, Intersection.BLACK);
        for (String s : new String[]{"E5", "F4", "E3", "D4"}) put(iso, s, Intersection.WHITE);
        RegimeWalker walker = new RegimeWalker(iso, null, null);
        assertEquals(Set.of(), walker.deadKos(iso.problem, Intersection.WHITE));
        // D5: D6 and C5
        Point d5 = Intersection.gtp2point("D5");
        assertEquals(Set.of(Intersection.gtp2point("D6"), Intersection.gtp2point("C5")),
                RegimeWalker.liberties(iso.problem.board, d5.x, d5.y));
    }

    @Test
    void aKoTheOtherSideCouldTakeIsOneToTakeUnlessItIsDead() throws Exception {
        // white to play, winning every ko: black could take at E4, and white would take back at
        // D4 and could fill E4, so the ko is real. katago playing white elsewhere here may be
        // giving it up, and the walk searches again from white's turn
        ProblemIsolator.IsolatedProblem iso = new ProblemIsolator.IsolatedProblem();
        iso.problem = new Node(null);
        iso.problem.defaultToMoveColor = Intersection.WHITE;
        for (String s : new String[]{"D5", "C4", "D3"}) put(iso, s, Intersection.BLACK);
        for (String s : new String[]{"E5", "F4", "E3", "D4"}) put(iso, s, Intersection.WHITE);
        RegimeWalker walker = new RegimeWalker(iso, null, null);
        assertTrue(walker.koToTake(iso.problem, Intersection.WHITE));
        // with black winning every ko, white has none to take
        assertFalse(walker.koToTake(iso.problem, Intersection.BLACK));
        // gp-62169 after B5 W B4 B A6: black's take at B5 is a dead ko, which is no ko at all
        ProblemIsolator.IsolatedProblem dead = problem();
        Node node = Positions.play(dead, moves("B B5,W B4,B A6"));
        assertFalse(new RegimeWalker(dead, null, null).koToTake(node, Intersection.WHITE));
    }

    @Test
    void eachSideKeepsEyesInTheFillForTheFreeExchanges() throws Exception {
        // gp-62416: the empty zone counts to black's wall, so balancing the areas alone gave
        // the whole fill to white, and white could never be given back a ko black took
        ProblemIsolator.IsolatedProblem iso = fromFile("gp-62416");
        boolean[][] zone = RegimeWalker.zone(iso);
        assertTrue(RegimeWalker.neutralPoints(iso, zone, Intersection.BLACK).size() >= BalancedFortress.EYES);
        assertTrue(RegimeWalker.neutralPoints(iso, zone, Intersection.WHITE).size() >= BalancedFortress.EYES);
    }

    @Test
    void aPointBannedByTheKoJustTakenIsNoEye() throws Exception {
        // gp-62623 after B E18 W D16 B F15 W B19: black takes at D19, white takes back at E19,
        // black's A19 puts B19 and C19 in atari, white takes it at A18, black takes at D19
        // again and white takes back. black could take A18 at A19, and white could take that
        // back but never fill A19; but D19, banned to black for this one move, would capture
        // B19 and C19 too, so A19 is no dead ko
        ProblemIsolator.IsolatedProblem iso = fromFile("gp-62623");
        RegimeWalker walker = new RegimeWalker(iso, null, null);
        boolean[][] zone = RegimeWalker.zone(iso);
        List<Point> w = RegimeWalker.neutralPoints(iso, zone, Intersection.WHITE);
        List<Point> b = RegimeWalker.neutralPoints(iso, zone, Intersection.BLACK);
        Node node = Positions.play(iso, moves("B E18,W D16,B F15,W B19,B D19," + exchange(w.get(0), b.get(0))
                + ",W E19,B A19,W A18,B D19," + exchange(w.get(1), b.get(1)) + ",W E19"));
        Point a19 = Intersection.gtp2point("A19");
        assertFalse(node.legalMove(Intersection.gtp2point("D19")));
        assertTrue(RegimeWalker.neverEnds(node, a19));
        assertFalse(RegimeWalker.onlyAKo(node, a19));
        assertEquals(Set.of(), walker.deadKos(node, Intersection.WHITE));
    }

    private static String exchange(Point mine, Point theirs) {
        return "W " + Intersection.toGTPloc(mine.x, mine.y, 19) + ",B " + Intersection.toGTPloc(theirs.x, theirs.y, 19);
    }

    @Test
    void twoKosTakenFromOnePositionAreADoubleKo() {
        // the other side's takes, by the position taken from: taking the same ko again from
        // there is only a repeat, but with another ko taken from there too it is a double ko
        Set<String> taken = new HashSet<>(Set.of("here|B D19"));
        assertFalse(RegimeWalker.anotherKo(taken, "here", "B D19"));
        taken.add("there|B A19");
        assertFalse(RegimeWalker.anotherKo(taken, "here", "B D19"));
        taken.add("here|B A19");
        assertTrue(RegimeWalker.anotherKo(taken, "here", "B D19"));
    }

    @Test
    void aKoTheOpponentTookAndLostIsFoundByTheExchange() throws Exception {
        RegimeWalker walker = new RegimeWalker(problem(), null, null);
        // black wins every ko: white's retake at B6 is answered by black's exchange (B19, then
        // white's T18) and black's retake at B5
        List<String> line = moves("B B5,W B4,B A6,W K18,B B5,W K14,B K15,W B6,B B19,W T18,B B5");
        Set<Integer> exchanges = walker.exchanges(line);
        assertEquals(Set.of(8, 9), exchanges);
        assertEquals(7, MoveClassifier.lastLostKo(line, exchanges, 2, "B"));
        // in the line where white wins the ko, black's exchange comes nowhere
        List<String> clean = moves("B B5,W B4,B A6,W K18,B K14,W C7,B K15,W B8,B B5,W T18,B B19,W B6");
        assertEquals(-1, MoveClassifier.lastLostKo(clean, walker.exchanges(clean), 2, "B"));
    }
}
