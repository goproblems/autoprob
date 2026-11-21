package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.Test;

import autoprob.go.parse.SgfEdit;

class CrawlReplayTest {
    // black's A19 is answered at B18 and C17 is right; black's C19 is wrong, answered at D19
    private static final String ORIGINAL = "(;SZ[19]AB[dd]AW[ee](;B[aa];W[bb];B[cc]C[RIGHT])(;B[ca];W[da]))";

    @Test
    void theLinesATreeAddsStartAtItsFirstNewNode() {
        // a new first move with its line, and the wrong line carried on to a correct end
        SgfEdit tree = SgfEdit.parse("(;SZ[19]AB[dd]AW[ee](;B[aa];W[bb];B[cc]C[RIGHT])(;B[ca];W[da];B[ea]C[RIGHT])"
                + "(;B[fa];W[ga](;B[ha]C[RIGHT])(;B[ia]C[RIGHT])))");
        Set<String> lines = CrawlReplay.lines(tree, SgfEdit.parse(ORIGINAL));
        assertEquals(Set.of("after B ca W da: B ea", "B fa W ga B ha", "B fa W ga B ia"), lines);
        // the nodes they add, a shared start counted once
        assertEquals(Set.of("after B ca W da: B ea ", "B fa ", "B fa W ga ", "B fa W ga B ha ", "B fa W ga B ia "), CrawlReplay.prefixes(lines));
        // the problem as it is adds nothing
        assertEquals(Set.of(), CrawlReplay.lines(SgfEdit.parse(ORIGINAL), SgfEdit.parse(ORIGINAL)));
    }

    @Test
    void thePositionsOfASuggestionsLinesAreLabeledByWhatReviewLeft() {
        // the crawl suggested B F19 with two lines, and review cut the first back to its third move
        SuggestionDraft draft = new SuggestionDraft();
        draft.boardSize = 19;
        draft.originalSgf = ORIGINAL;
        draft.suggestedSgf = "(;SZ[19]AB[dd]AW[ee](;B[aa];W[bb];B[cc]C[RIGHT])(;B[ca];W[da])"
                + "(;B[fa];W[ga](;B[ha];W[ja];B[ka]C[RIGHT])(;B[ia]C[RIGHT])))";
        SuggestionDraft.Place place = new SuggestionDraft.Place();
        place.lines = List.of(List.of("B fa", "W ga", "B ha", "W ja", "B ka"), List.of("B fa", "W ga", "B ia"));
        draft.places = List.of(place);
        String reviewed = "(;SZ[19]AB[dd]AW[ee](;B[aa];W[bb];B[cc]C[RIGHT])(;B[ca];W[da])"
                + "(;B[fa];W[ga](;B[ha]C[RIGHT])(;B[ia]C[RIGHT])))";
        List<LineEndStudy.Spot> spots = new LineEndStudy(new Properties()).spots(draft, reviewed);
        // the ends first, then the position the lines go on from; what review cut away is left out
        assertEquals(List.of(
                new LineEndStudy.Spot(List.of(), List.of("B F19", "W G19", "B H19"), "END", true),
                new LineEndStudy.Spot(List.of(), List.of("B F19", "W G19", "B J19"), "END", false),
                new LineEndStudy.Spot(List.of(), List.of("B F19"), "CONT", true)), spots);
    }
}
