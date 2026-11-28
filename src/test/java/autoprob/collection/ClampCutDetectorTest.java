package autoprob.collection;

import autoprob.go.Node;
import autoprob.go.parse.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The positions are GoProblems problems, named by their id; the trees are the site's own. */
class ClampCutDetectorTest {
    // black D4 has white C4 between it and B4: white can connect it to the stones above at C5 or
    // to those below at C3, not both
    private static final String PROBLEM_45743 = "(;SZ[19]AW[br][cr][dr][cp][do][eo][fo]AB[bo][bp][bq][cn][eq][er][gq]"
            + "(;B[dp];W[co];B[cq]C[RIGHT])(;B[cq];W[dq])(;B[co];W[dp])(;B[ep];W[dp])(;B[dq];W[cq]))";

    private final ClampCutDetector detector = new ClampCutDetector();

    @Test
    void recognizesThePlainClamp() throws Exception {
        CollectionDetector.MatchResult result = detector.detect(parse(PROBLEM_45743));
        assertTrue(result.matches(), result.reason());
        assertEquals("black D4 clamps white C4 against black B4, leaving it C5 and C3: white takes C5 and black C3",
                result.reason());
    }

    @Test
    void recognizesAClampThatTouchesOnlyTheStoneItClamps() throws Exception {
        // 46424: black F5 is in contact with nothing but F4, so white has no atari on it
        CollectionDetector.MatchResult result = detector.detect(parse("(;SZ[19]AW[cq][do][fp][cr][br][bp][aq][dp]AB[cm][fq][bo][co][cp][dq][dr][dn]"
                + "(;B[fo](;W[gp]C[CHOICE](;B[ep]C[RIGHT])(;B[eo];W[ep]))(;W[ep]C[CHOICE];B[gp]C[RIGHT]))"
                + "(;B[ep];W[eo];B[fo];W[eq](;B[en];W[ep])(;B[er];W[fn]))(;B[gp];W[fo])(;B[gq];W[fo])(;B[eo];W[ep]))"));
        assertTrue(result.matches(), result.reason());
        assertEquals("black F5 clamps white F4 against black F3, leaving it G4 and E4: white takes G4 and black E4",
                result.reason());
    }

    @Test
    void allowsTheAtariOnTheClampStoneBeforeTheTwoPoints() throws Exception {
        // 41042: black E16 peeps where D16 and E15 meet, so white can put it in atari first
        CollectionDetector.MatchResult result = detector.detect(parse("(;SZ[19]AB[bb][bc][cc][cd][ce][fc][gb][gc][ic]AW[ba][cb][db][eb][fb][dd][ee][fe]"
                + "(;B[ed](;W[fd]C[CHOICE];B[ec];W[dc];B[de]C[RIGHT])(;W[de]C[CHOICE];B[dc]C[RIGHT]))"
                + "(;B[de];W[ed])(;B[ec];W[dc])(;B[dc];W[ec])(;B[fd];W[ed]))"));
        assertTrue(result.matches(), result.reason());
        assertEquals("black E16 clamps white D16 against black C16, leaving it D17 and D15: white ataris at F16 and "
                + "black extends to E17, white takes D17 and black D15", result.reason());
    }

    @Test
    void allowsTheAtariOnTheClampStoneAfterTheTwoPoints() throws Exception {
        // 40994: here white takes a point first and puts D17 in atari afterwards
        CollectionDetector.MatchResult result = detector.detect(parse("(;SZ[19]AB[bb][bc][bd][cd][fd][fc][fb]AW[cb][db][ec][de][ee][bf][cf]"
                + "(;B[dc](;W[eb]C[CHOICE];B[ed];W[dd];B[cc]C[RIGHT])(;W[cc]C[CHOICE];B[dd];W[ed];B[eb]C[RIGHT])"
                + "(;W[ed]C[CHOICE];B[eb](;W[cc];B[dd]C[RIGHT])(;W[dd];B[cc]C[RIGHT]))"
                + "(;W[dd]C[CHOICE](;B[cc](;W[ed];B[eb]C[RIGHT])(;W[eb];B[ed]C[RIGHT]))(;B[eb];W[cc])(;B[ed];W[cc])))"
                + "(;B[eb];W[dc])(;B[ed];W[dd])(;B[dd];W[ed])(;B[ba];W[eb])(;B[ae];W[eb]))"));
        assertTrue(result.matches(), result.reason());
        assertEquals("black D17 clamps white E17 against black F17, leaving it E16 and E18: white takes E18 and "
                + "black E16, then white ataris at D16 and black extends to C17", result.reason());
    }

    @Test
    void takesALineThatEndsWithTheClampStoneExtended() throws Exception {
        // 47072: the tree stops once black has answered the atari; the two points are left to the reader
        CollectionDetector.MatchResult result = detector.detect(parse("(;SZ[13]AB[bk][bj][cl][ci][di][ei][fi]AW[bi][bh][cg][ck][cj][ej][fk][hk]"
                + "(;B[ek];W[el](;B[dk]C[RIGHT])(;B[dj];W[dk])(;B[fj];W[dk])(;B[dl];W[dk]))(;B[dk];W[dj])(;B[dj];W[dk])"
                + "(;B[gk];W[dl];B[bl];W[gl])(;B[el];W[ek])(;B[dl];W[ek])(;B[al];W[dl])(;B[fj];W[ek]))"));
        assertTrue(result.matches(), result.reason());
        assertTrue(result.reason().endsWith("white ataris at E2 and black extends to D3, and the line ends with "
                + "D4 and F4 not both to be had by white"), result.reason());
    }

    @Test
    void takesAStoneHeldBetweenTwoDiagonalStones() throws Exception {
        // 55821: nothing stands behind white E4, but black D5 and D3 each stand beside one of the points
        CollectionDetector.MatchResult result = detector.detect(parse("(;SZ[19]AB[dq][cq][do][co][cr][gq][hr][hq][ho][jr]AW[dr][fr][gr][ep][fo][fn]"
                + "(;B[fp];W[eo](;B[eq]C[RIGHT])(;B[fq];W[eq])(;B[er];W[fq](;B[gp];W[eq])(;B[eq];W[gp]))(;B[dp];W[eq]))"
                + "(;B[eq];W[fq])(;B[fq];W[eq])(;B[eo];W[fq])(;B[er];W[fq])(;B[dp];W[fq])"
                + "(;B[gp];W[eq](;B[eo];W[fp])(;B[fp];W[eo])))"));
        assertTrue(result.matches(), result.reason());
        assertEquals("black F4 clamps white E4 against black D5 and D3, leaving it E5 and E3: white takes E5 and black E3",
                result.reason());
    }

    @Test
    void saysWhenOneOfTheTwoPointsIsOnTheEdge() throws Exception {
        String[] positions = {
                // 46352, "Black to connect": white C18 is on the second line, and black gets C19
                "(;SZ[19]AB[bb][fc][cf][gc][ec][ba][bc][ce]AW[dc][hc][de][cb][dd][fe][hd]"
                        + "(;B[db](;W[eb]C[CHOICE];B[da];W[cc];B[ca]C[RIGHT])(;W[cc]C[CHOICE];B[ca]C[RIGHT]))"
                        + "(;B[cc];W[db])(;B[ca];W[da])(;B[eb];W[db])(;B[ea];W[db])(;B[da];W[ca]))",
                // 7746, white to play: black ataris the clamp stone twice, and it reaches G1 in extending
                "(;SZ[19]AW[eq][dq][cp][cn][go][io][ip][hq][hr][hs]AB[ep][fp][fq][jp][jo][iq][ir][jr][kq][in][en][gr][fm]"
                        + "(;W[gq];B[fr])(;W[fr];B[er];W[fs];B[es];W[gs]C[RIGHT]))"
        };

        for (String sgf : positions) {
            CollectionDetector.MatchResult result = detector.detect(parse(sgf));
            assertTrue(result.matches(), result.reason());
            assertTrue(result.reason().contains("the edge point"), result.reason());
        }
    }

    @Test
    void leavesOutAClampThatOnlyStartsSomethingLonger() throws Exception {
        // 28330: the two points are settled at move 5 and the line goes on to a capturing race
        CollectionDetector.MatchResult result = detector.detect(parse("(;SZ[19]AB[pb][pd][qd][qh][rd][rh][sd][sh]AW[nb][nd][oe][pf][ph][pi][qf][qj][re][rj];B[rf]"
                + "(;W[rg]C[CHOICE];B[sf](;W[qe]C[CHOICE];B[se];W[qg];B[sg]C[RIGHT])(;W[qg]C[CHOICE];B[sg];W[qe];B[se]C[RIGHT]))"
                + "(;W[sf]C[CHOICE];B[rg];W[qe];B[se]C[RIGHT]))"));
        assertFalse(result.matches(), result.reason());
        assertTrue(result.reason().contains("the line goes on 2 moves after that"), result.reason());
    }

    @Test
    void rejectsAStoneThatIsNotPlayedAsAClamp() throws Exception {
        String[][] positions = {
                // 34852: black F16 has white F17 between it and its own stones, but is joined to others
                {"(;SZ[19]AB[db][dc][ea][ed][fb][gb][hc][hd]AW[eb][ec][eg][fc][fe][ff][gd][gg][he][hf]"
                        + "(;B[fd];W[gc];B[ge]C[RIGHT])(;B[gc];W[fd]))", "joins stones of its own"},
                // 31606: black O19 goes under white O18 from the first line
                {"(;SZ[19]AB[lb][mc][nc][oc][pa][pb]AW[jc][kc][lc][md][nb][nd][od][pc][qb][qc]"
                        + "(;B[na];W[ma];B[ob]C[RIGHT])(;B[mb];W[na])(;B[ob];W[na])(;B[kb];W[mb]))", "from the first line"},
                // 29111: black Q16 is a throw-in
                {"(;SZ[19]AB[nc][nd][ob][oe][pb][rb][rc][rd]AW[mb][mc][nb][oc][od][pe][qd][qg]"
                        + "(;B[pd];W[pc];B[qc];W[pd];B[pf];W[qe](;B[qf]C[RIGHT])(;B[re];W[qf]))(;B[pc];W[pd]))",
                        "can be taken at once"},
                // 34620: neither point beside white R17 is next to another white stone
                {"(;SZ[19]AB[pb][pc][pd][pe][qe][ra][re][sb]AW[nb][nd][ob][oe][of][pa][pf][qc][qf][rf][sd][se][sf]"
                        + "(;B[rc];W[qb];B[qa];W[rd];B[qd]C[RIGHT])(;B[rd];W[rc];B[sc];W[rb]))", "nothing to cut it from"}
        };

        for (String[] position : positions) {
            CollectionDetector.MatchResult result = detector.detect(parse(position[0]));
            assertFalse(result.matches(), result.reason());
            assertTrue(result.reason().contains(position[1]), result.reason());
        }
    }

    @Test
    void rejectsAClampWhoseSolutionDoesNotShowTheTwoPoints() throws Exception {
        String[][] positions = {
                // 32962: white answers black Q18 at P18, which is neither point and no atari
                {"(;SZ[19]AB[mb][mc][nd][ne][pd][pe][qe][re][sd][se]AW[nb][pc][qd][ra][rc][rd]"
                        + "(;B[pb](;W[ob]C[CHOICE];B[rb];W[sb];B[qb];W[pa];B[sc]C[RIGHT])(;W[qb]C[CHOICE];B[sb];W[ob];B[rb]C[RIGHT]))"
                        + "(;B[rb];W[sb];B[qb];W[pb];B[sc];W[qc]))", "neither takes one of the two points"},
                // 39501: white takes O17 and black answers at Q19, not at the other point
                {"(;SZ[19]AB[ma][mb][mc][na][nd][od][pd][qa][qb][qc][qd]AW[la][lb][lc][ld][md][ne][oc][oe][pb][pe][qe][ra][rb][rc][rd]"
                        + "(;B[ob](;W[nc]C[CHOICE];B[pa]C[RIGHT])(;W[pa];B[nc]C[RIGHT]))(;B[pa];W[ob])(;B[nc];W[ob])(;B[oa];W[ob]))",
                        "the solution does not take"},
                // 38752: white puts black R17 in atari and black does not extend it
                {"(;SZ[19]AB[qe][qf][ra][rf][sb][sc][se]AW[ob][od][pc][pf][pg][qg][rc][re][rg][ri]"
                        + "(;B[qc];W[qb](;B[rb];W[qd];B[rd]C[RIGHT])(;B[qd];W[rb]))(;B[rd];W[rb]))", "does not extend it"},
                // 62370: the tree calls the clamp right and says no more
                {"(;SZ[13]AB[jd][jc][jb][kb][lb][le][gc]AW[kc][kd][ke][kf][je][id][ib](;B[hd]C[RIGHT])(;B[ic];W[hd])"
                        + "(;B[hc];W[ic])(;B[ie];W[hd])(;B[lc];W[ic])(;B[ld];W[ic])(;B[mc];W[ic])(;B[md];W[ic])(;B[hb];W[ic]))",
                        "the tree ends at the clamp"}
        };

        for (String[] position : positions) {
            CollectionDetector.MatchResult result = detector.detect(parse(position[0]));
            assertFalse(result.matches(), result.reason());
            assertTrue(result.reason().contains(position[1]), result.reason());
        }
    }

    @Test
    void rejectsOrdinaryMoves() throws Exception {
        // 41734: a capture
        CollectionDetector.MatchResult capture = detector.detect(parse("(;SZ[19]AB[cr][dq][eq][fr][es]AW[er][dr][fq][gr];B[ds]C[RIGHT])"));
        assertFalse(capture.matches(), capture.reason());

        // the position of 45743 with a tree that calls the direct cut right as well
        CollectionDetector.MatchResult either = detector.detect(parse("(;SZ[19]AW[br][cr][dr][cp][do][eo][fo]AB[bo][bp][bq][cn][eq][er][gq]"
                + "(;B[dp];W[co];B[cq]C[RIGHT])(;B[cq]C[RIGHT]))"));
        assertFalse(either.matches(), either.reason());

        // and with one that calls nothing right
        assertFalse(detector.detect(parse("(;SZ[19]AW[br][cr][dr][cp][do][eo][fo]AB[bo][bp][bq][cn][eq][er][gq]"
                + "(;B[dp];W[co])(;B[cq];W[dq]))")).matches());
    }

    @Test
    void namesTheSiteCollection() {
        assertEquals("clamp-cut", detector.name());
        assertEquals(445, detector.siteCollection());
    }

    private Node parse(String sgf) throws Exception {
        return new Parser().parse(sgf);
    }
}
