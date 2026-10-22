package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class TreeComparisonTest {
    private static List<String> line(String moves) {
        return Arrays.asList(moves.split(", "));
    }

    @Test
    void reportsMissingAndExtraMovesOnEachSide() {
        // the author has two first moves (D1, E1) and two resistances after D1
        List<List<String>> author = List.of(
                line("B D1, W F1, B G1"),
                line("B D1, W H1, B J1"),
                line("B E1, W F1, B G1"));
        // the tree finds D1 only, one resistance, and an extra answer K1 after F1
        List<List<String>> generated = List.of(
                line("B D1, W F1, B G1"),
                line("B D1, W F1, B K1"));
        TreeComparison tc = TreeComparison.compare(generated, author);
        assertEquals(1, tc.reproduced);
        assertEquals(List.of("root: B E1"), tc.missingSolverMoves);
        assertEquals(List.of("B D1 W F1: B K1"), tc.extraSolverMoves);
        assertEquals(List.of("B D1: W H1"), tc.missingResponses);
        assertEquals(List.of(), tc.extraResponses);
        assertEquals(8, tc.authorNodes);
        assertEquals(4, tc.generatedNodes);
        assertEquals(3, tc.matchedNodes);
    }

    @Test
    void reportsWhereOneTreeEndsAndTheOtherContinues() {
        List<List<String>> author = List.of(line("B D1, W F1, B G1"));
        List<List<String>> generated = List.of(line("B D1, W F1, B G1, W H1, B J1"));
        TreeComparison tc = TreeComparison.compare(generated, author);
        assertEquals(List.of("B D1 W F1 B G1"), tc.endsLate);
        assertEquals(List.of(), tc.endsEarly);

        TreeComparison reverse = TreeComparison.compare(author, generated);
        assertEquals(List.of("B D1 W F1 B G1"), reverse.endsEarly);
    }
}
