package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import autoprob.go.Node;
import autoprob.go.parse.Parser;
import autoprob.katastruct.KataAnalysisResult;
import autoprob.katastruct.MoveInfo;

class KoLinesTest {
    // white F14 is held by black E14 F15 F13; black G14 takes it inside white G15 H14 G13,
    // and white could take straight back
    private static final String KO = "(;SZ[19]PL[B]AB[ef][fe][fg]AW[ff][hf][ge][gg])";

    @Test
    void aRetakeableSingleStoneCaptureIsAKo() throws Exception {
        Node root = new Parser().parse(KO);
        assertEquals("B G14", KoLines.koIn(root, List.of(List.of("B G14"))));
        assertNull(KoLines.koIn(root, List.of(List.of("B C17"))));
        assertEquals(0, root.babies.size()); // the board is left as it was
    }

    @Test
    void aKoCountsAsFoughtWhenItIsTakenBackOrAThreatIsPlayed() throws Exception {
        Node root = new Parser().parse(KO);
        // taken and left: a ko no one fights over
        assertNull(KoLines.koFight(root, List.of(List.of("B G14", "W A1", "B A2")), Set.of()));
        // white plays a threat (A1 is on the threat pattern), black answers
        assertEquals("B G14", KoLines.koFight(root, List.of(List.of("B G14", "W A1", "B A2")), Set.of("A1")));
        // white takes back after an exchange elsewhere
        assertEquals("B G14", KoLines.koFight(root, List.of(List.of("B G14", "W A1", "B A2", "W F14")), Set.of()));
        // a move on the threat pattern long after the ko is endgame, not a ko threat
        assertNull(KoLines.koFight(root, List.of(List.of("B G14", "W C1", "B C2", "W C3", "B C4", "W A1")), Set.of("A1")));
        assertEquals(0, root.babies.size());
    }

    @Test
    void katagoLinesFollowEverySeriousAnswer() throws Exception {
        // after black C17 katago's best answer is white A1, and black then takes the ko at G14.
        // B1 got a tenth of A1's visits and is followed too; C1 got less
        KataAnalysisResult kar = new KataAnalysisResult();
        kar.moveInfos = List.of(info(1000, "A1", "G14", "pass", "A2"), info(100, "B1", "B2"), info(99, "C1"));
        List<List<String>> lines = KoLines.katagoLines(List.of("B C17"), kar);
        assertEquals(List.of(List.of("B C17", "W A1", "B G14"), List.of("B C17", "W B1", "B B2")), lines);
        assertEquals("B G14", KoLines.koIn(new Parser().parse(KO), lines));
    }

    private static MoveInfo info(int visits, String... pv) {
        MoveInfo mi = new MoveInfo();
        mi.move = pv[0];
        mi.visits = visits;
        mi.pv = List.of(pv);
        return mi;
    }
}
