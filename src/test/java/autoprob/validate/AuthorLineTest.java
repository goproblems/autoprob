package autoprob.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

// the lines the site's reviewer put in place of the ones sent, for the ko suggestions it
// approved: each is the author's own line with the found move's exchange put in before it
class AuthorLineTest {
    @Test
    void aFoundMoveThatIsTheAuthorsOwnPlayedSoonerComesBackToTheAuthorsEnd() {
        // gp-60804: the author's B B2, W B1, B A3, W A2, B A1, which takes the ko. the found
        // move is A3 first, and the site's line B A3, W A2, B B2, W B1, B A1
        List<List<String>> lines = List.of(List.of("B B2", "W B1", "B A3", "W A2", "B A1"));
        List<String> line = AuthorLine.follow(lines, "B A3");
        assertEquals(lines.get(0), line);
        assertEquals("W A2", AuthorLine.answer(lines, List.of("B A3")));
        assertEquals("B B2", AuthorLine.next(line, List.of("B A3", "W A2")));
        assertEquals("W B1", AuthorLine.answer(lines, List.of("B A3", "W A2", "B B2")));
        assertEquals("B A1", AuthorLine.next(line, List.of("B A3", "W A2", "B B2", "W B1")));
        assertFalse(AuthorLine.done(line, List.of("B A3", "W A2", "B B2", "W B1")));
        assertTrue(AuthorLine.done(line, List.of("B A3", "W A2", "B B2", "W B1", "B A1")));
        assertNull(AuthorLine.next(line, List.of("B A3", "W A2", "B B2", "W B1", "B A1")));
    }

    @Test
    void anExchangeBeforeTheAuthorsLineIsFollowedByAllOfIt() {
        // gp-61398 after B F2, W E2, B E1, W F4: the author's D2, W D3, B E3, which takes the
        // ko. the found F5 and its answer E5 come first, and the site's line goes on D2, D3, E3
        List<List<String>> lines = List.of(List.of("B D2", "W D3", "B E3"));
        List<String> line = AuthorLine.follow(lines, "B F5");
        assertNull(AuthorLine.answer(lines, List.of("B F5")));
        assertEquals("B D2", AuthorLine.next(line, List.of("B F5", "W E5")));
        assertEquals("W D3", AuthorLine.answer(lines, List.of("B F5", "W E5", "B D2")));
        assertEquals("B E3", AuthorLine.next(line, List.of("B F5", "W E5", "B D2", "W D3")));
        assertTrue(AuthorLine.done(line, List.of("B F5", "W E5", "B D2", "W D3", "B E3")));
    }

    @Test
    void ofTheAuthorsLinesTheShortestIsFollowedOrTheOneWithTheMove() {
        // gp-61669 after W J2, B L1, W H1, B N2: the author's K1, or M4 or N4 with B M3 and
        // then K1. the found N5 is none of them, and the site's line W N5, B M3, W K1
        List<List<String>> lines = List.of(List.of("W M4", "B M3", "W K1"), List.of("W K1"), List.of("W N4", "B M3", "W K1"));
        assertEquals(List.of("W K1"), AuthorLine.follow(lines, "W N5"));
        assertNull(AuthorLine.answer(lines, List.of("W N5")));
        assertTrue(AuthorLine.done(List.of("W K1"), List.of("W N5", "B M3", "W K1")));
        // gp-62045 after W M2, B L5: the author plays H2 and its answer G2 later on some of
        // its lines. the found move is H2 first, and the site's line W H2, B G2, W N3, B K5, W N1
        lines = List.of(List.of("W N3", "B K5", "W N1"), List.of("W N3", "B K5", "W H2", "B G2", "W N1"),
                List.of("W N3", "B K5", "W K1", "B J1", "W H2", "B G2", "W N1"));
        List<String> line = AuthorLine.follow(lines, "W H2");
        assertEquals(lines.get(1), line);
        assertEquals("B G2", AuthorLine.answer(lines, List.of("W H2")));
        assertEquals("W N3", AuthorLine.next(line, List.of("W H2", "B G2")));
        assertEquals("B K5", AuthorLine.answer(lines, List.of("W H2", "B G2", "W N3")));
        assertEquals("W N1", AuthorLine.next(line, List.of("W H2", "B G2", "W N3", "B K5")));
        assertTrue(AuthorLine.done(line, List.of("W H2", "B G2", "W N3", "B K5", "W N1")));
        // no lines, nothing to follow, and nothing is ever played out
        assertEquals(List.of(), AuthorLine.follow(List.of(), "B A1"));
        assertFalse(AuthorLine.done(List.of(), List.of("B A1")));
        assertNull(AuthorLine.answer(lines, List.of()));
    }
}
