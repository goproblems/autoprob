package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import autoprob.go.Intersection;
import autoprob.go.Node;
import autoprob.go.parse.Parser;
import autoprob.go.parse.SmallBoard;

class LevelCheckerTest {
    @Test
    void aLineIsAPositionAndTheLevelsOfItsMoves() {
        List<LevelChecker.Entry> entries = LevelChecker.parse(List.of(
                "# why",
                "gp-62169 | start | B B5 CLEAN",
                "gp-32711 | B O18,W N18 | B T16 CLEAN, B T15 !CLEAN, B S16 ?"));
        assertEquals(2, entries.size());
        assertEquals(List.of(), entries.get(0).at());
        assertEquals(List.of(new LevelChecker.Judged("B B5", "CLEAN")), entries.get(0).moves());
        assertEquals(List.of("B O18", "W N18"), entries.get(1).at());
        assertEquals(new LevelChecker.Judged("B T15", "!CLEAN"), entries.get(1).moves().get(1));
    }

    @Test
    void aLevelItDoesNotKnowIsAnError() {
        assertThrows(IllegalArgumentException.class, () -> LevelChecker.parse(List.of("gp-1 | start | B A1 DEAD")));
        assertThrows(IllegalArgumentException.class, () -> LevelChecker.parse(List.of("gp-1 | B A1 CLEAN")));
    }

    @Test
    void notCleanIsAnyLevelButClean() {
        assertTrue(LevelChecker.meets("!CLEAN", MoveClassifier.Level.KO));
        assertTrue(LevelChecker.meets("!CLEAN", MoveClassifier.Level.FAIL));
        assertFalse(LevelChecker.meets("!CLEAN", MoveClassifier.Level.CLEAN));
        assertTrue(LevelChecker.meets("?", MoveClassifier.Level.FAIL));
        assertFalse(LevelChecker.meets("KO", MoveClassifier.Level.SEKI));
    }

    // the file the check reads: every position can be played on its problem, in turn, and
    // every move judged there is legal for the side to play
    @Test
    void theLevelsFileReadsAndItsMovesCanBePlayed() throws Exception {
        List<LevelChecker.Entry> entries = LevelChecker.parse(Files.readAllLines(Path.of("cases/levels.txt")));
        assertFalse(entries.isEmpty());
        for (LevelChecker.Entry e : entries) {
            String sgf = Files.readString(Path.of("sample_problems", e.problem() + ".sgf"));
            Node node = new Parser().parse(SmallBoard.toNineteen(sgf).sgf());
            for (String m : e.at()) node = play(node, m, e);
            for (LevelChecker.Judged j : e.moves()) play(node, j.move(), e);
        }
    }

    private static Node play(Node node, String move, LevelChecker.Entry e) throws Exception {
        assertEquals(IsolationRecord.colorLetter(node.getToMove()), move.substring(0, 1), "out of turn " + move + " in " + e);
        Point p = Intersection.gtp2point(move.substring(2));
        assertTrue(node.legalMove(p), "illegal " + move + " in " + e);
        return node.addBasicMove(p.x, p.y);
    }
}
