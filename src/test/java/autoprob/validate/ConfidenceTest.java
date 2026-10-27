package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ConfidenceTest {
    private static Confidence.Evidence missing() {
        Confidence.Evidence e = new Confidence.Evidence();
        e.missing = true;
        return e;
    }

    @Test
    void aFindingWithNothingForOrAgainstIt() {
        Confidence c = Confidence.of(missing());
        assertEquals("probably right (70-80%)", c.text());
        assertEquals(List.of(), c.reasons);
    }

    @Test
    void likeTheFindingAReviewRejected() {
        // gp-62080's B1: the author had it as wrong, and its line went through a ko
        Confidence.Evidence e = missing();
        e.kos = 1;
        e.authorRejects = true;
        Confidence c = Confidence.of(e);
        assertEquals("uncertain (30-60%)", c.text());
        assertTrue(c.explained().contains("Against it: its line goes through a ko"), c.explained());
    }

    @Test
    void likeTheFindingAReviewConfirmed() {
        // gp-62070's R19: the author plays it at the start, and accepts what the finder finds best
        Confidence.Evidence e = missing();
        e.authorPlaysElsewhere = true;
        e.authorAgrees = true;
        Confidence c = Confidence.of(e);
        assertEquals("likely right (80-90%)", c.text());
        assertTrue(c.explained().startsWith("likely right (80-90%). For it: "), c.explained());
    }

    @Test
    void reachingTheAuthorsLineIsStrongEvidence() {
        Confidence.Evidence e = missing();
        e.transposes = true;
        assertEquals("likely right (85-95%)", Confidence.of(e).text());
    }

    @Test
    void tellingTheAuthorAMoveIsWrongStartsLower() {
        assertEquals("probably right (65-75%)", Confidence.of(new Confidence.Evidence()).text());
    }

    @Test
    void theOldDetectorsRulesCountAgainstIt() {
        Confidence.Evidence e = missing();
        e.koSpot = true;
        assertEquals("probably right (60-80%)", Confidence.of(e).text());
        e = missing();
        e.distance = 3.2;
        Confidence far = Confidence.of(e);
        assertEquals("probably right (55-75%)", far.text());
        assertTrue(far.explained().contains("3.2 lines from the nearest stone, more like a tenuki"), far.explained());
        e.distance = 1.0;
        e.outside = true;
        assertTrue(Confidence.of(e).explained().contains("on the outside of the problem"));
    }

    @Test
    void everythingAgainstItStaysInBounds() {
        Confidence.Evidence e = missing();
        e.kos = 2;
        e.searched = 3;
        e.margin = 0.6;
        e.authorRejects = true;
        e.isolation.addAll(List.of("komi was rebalanced", "the stakes only settled on a deeper retry",
                "no stones seemed to be at stake", "one more"));
        Confidence c = Confidence.of(e);
        assertEquals("doubtful", c.label);
        assertTrue(c.low >= 1 && c.high <= 99 && c.low < c.high, c.text());
        assertEquals(5, c.reasons.size(), "one reason per kind of evidence");
    }
}
