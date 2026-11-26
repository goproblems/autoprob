package autoprob.collection;

import autoprob.go.Node;
import autoprob.go.parse.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The positions are GoProblems problems, named by their id; the trees are the site's own. */
class SacrificeForLibertyDetectorTest {
    // black B5 is the stone given up: white's two stones at B3 are then too short of liberties to
    // play A3, and taking B5 first costs white the move the race is lost by
    private static final String PROBLEM_43924 = "(;SZ[19]AB[ar][br][bs][cp][cq][dp][ep][fq][fr]AW[bq][bp][cr][cs][dr][dq][co][cn](;B["
            + "bo];W[bn](;B[er]C[RIGHT])(;B[eq]C[RIGHT])(;B[ds]C[RIGHT])(;B[es];W[ao])(;B[ao];W[an]"
            + "(;B[eq]C[RIGHT])(;B[er]C[RIGHT])(;B[ds]C[RIGHT])))(;B[eq];W[aq])(;B[er];W[aq])(;B[ds"
            + "];W[aq])(;B[aq];W[bo])(;B[ap];W[bo]))";

    // black A18 is added to the cutting stone at B18: white cannot fill A16 or B16 until both are taken
    private static final String PROBLEM_4500 = "(;AB[bb]AW[cb]AW[dc]AW[db]AW[bc]AW[ac]AB[cc]AB[cd]AB[dd]AB[ce]AB[be]AB[ae]AW[ed]AW[e"
            + "e]AW[de]AW[cf]AW[bf]AW[af]AB[df]AB[dg]AB[bh]TR[cc]TR[cd]TR[dd]TR[ce]TR[be]TR[ae]C[bl"
            + "acktosavehismarkedstones](;B[ab];W[ba](;B[cg];W[aa](;B[bg]C[RIGHT])(;B[ag]C[RIGHT]))"
            + "(;B[bg]C[NOTTHIS])(;B[ag]C[NOTTHIS]))(;B[bd];W[ad])(;B[ad];W[bd])(;B[cg];W[ad])(;B[b"
            + "g];W[ad])(;B[ag];W[ad])(;B[ba];W[ad]))";

    /** A stone given up alone, and a stone given up with the stones it joins: the two collections. */
    private final SacrificeForLibertyDetector detector = SacrificeForLibertyDetector.loneStone();
    private final SacrificeForLibertyDetector another = SacrificeForLibertyDetector.addedStone();

    @Test
    void recognizesTheProblemTheCollectionStartedFrom() throws Exception {
        CollectionDetector.MatchResult result = detector.detect(parse(PROBLEM_43924));
        assertTrue(result.matches(), result.reason());
        assertEquals("black B5 is given up: white then needs 4 moves, not 2, to capture the 3 stones at A2",
                result.reason());
    }

    @Test
    void recognizesTheSameSacrificeElsewhere() throws Exception {
        String[] positions = {
                // 56585: the same shape at the top
                "(;SZ[13]AB[ab][bb][ba][cb][cc][cd][de][ee][ef][fd][ge][gc][gb]AW[ca][da]"
                        + "[db][dc][dd][ed][fc][fb][eb][ea][bc][bd][ce][cf][df](;B[be];W[bf](;B[ga]"
                        + ";W[ae];B[fa];W[ac];B[ec]C[RIGHT])(;B[ae];W[af];B[ga];W[ad];B[fa]C[RIGHT]"
                        + ")(;B[ac];W[ae])(;B[ad];W[ae]))(;B[ga];W[ac])(;B[fa];W[ga](;B[ha];W[ac])("
                        + ";B[be];W[hb]))(;B[ac];W[ad])(;B[ad];W[be]))",
                // 49159: the stone takes a liberty from two white chains at once
                "(;SZ[13]AB[la][ka][kb][kc][jc][id][hd][hc][hb]AW[ic][ib][ja][jd][kd][lc]"
                        + "[lb][mb](;B[ld];W[le](;B[ha];W[md];B[ia]C[RIGHT])(;B[md];W[me];B[ha];W[m"
                        + "c](;B[ia]C[RIGHT])(;B[ld];W[md];B[ia]C[RIGHT])))(;B[ha];W[ma])(;B[ia];W["
                        + "ha];B[ga];W[ma]))",
                // 48899 and 46849: the stone is taken in the solution, and the race still won
                "(;SZ[13]AW[la][lb][kb][jc][ic][kd][ke][le][md]AB[kc][lc][mc][mb][jd][je]"
                        + "[jf][kf][lf](;B[jb];W[ib](;B[mf];W[ja];B[me];W[ma];B[ld]C[RIGHT])(;B[ja]"
                        + ";W[mf];B[mg];W[ia];B[me]))(;B[mf];W[ma])(;B[ld];W[ma])(;B[me];W[mf];B[jb"
                        + "];W[ib](;B[mg];W[ja];B[me])(;B[ja];W[lg])))",
                "(;SZ[13]AB[gc][je][jg][lb][mb][lc][kd][ke][jc][ic][hb][ia]AW[ib][jb][kc]"
                        + "[ka][kb][la][ld][le][md][kf][kg][kh]AB[jf](;B[lf];W[lg](;B[ha];W[mf];B[j"
                        + "a];W[mc];B[ma]C[RIGHT])(;B[mf];W[mg];B[ha]C[RIGHT]))(;B[ha];W[mc]))",
                // 50401: the chain saved is a different one from the stones that then attack
                "(;SZ[13]AW[cm][bm][cl][ck][dl][ek][ej][gj][bj][aj][bi][bh][ci][ij]AB[ah]"
                        + "[bg][cg][ch][di][ei][dj][dk][cj][eg][bk][bl][al](;B[el];W[fl](;B[ag];W[e"
                        + "m];B[ai]C[RIGHT])(;B[em];W[fm];B[ag];W[dm];B[ai]C[RIGHT]))(;B[ag];W[am])"
                        + ")",
                // 54802: on the first line, for a group that has an eye already
                "(;SZ[13]AW[la][lb][kc][jc][kd][ke][je][ie][he][gd][gc][hb][ha][fb][cb]AB"
                        + "[ka][jb][kb][ia][ib][ic][id][hd][jd][ge][fe][fd][fc][eb][cd][bd](;B[ga]("
                        + ";W[fa]C[CHOICE];B[ea];W[gb]C[RIGHT])(;W[gb](;B[ea];W[fa]C[RIGHT])(;B[fa]"
                        + ";W[ea](;B[da];W[ec];B[fa];W[dc])(;B[fa];W[ec];B[da];W[dc])(;B[ec];W[fa])"
                        + "(;B[db];W[hc]))))(;B[fa];W[hc])(;B[gb];W[ga];B[ea];W[hc])(;B[ea];W[hc])("
                        + ";B[db];W[hc]))"
        };

        for (String sgf : positions) {
            CollectionDetector.MatchResult result = detector.detect(parse(sgf));
            assertTrue(result.matches(), result.reason());
        }
    }

    @Test
    void countsNoWayRoundTheStoneThatShortensTheChainBeingRaced() throws Exception {
        // 47577: black M8 is the cut, and white's N10 would then leave two stones on one liberty.
        // With black never answering, white could go round the top instead, M13 and N13, and
        // capture at N10 in three moves, one more than before, which would only pay for the stone.
        // But that lengthens the three white stones black goes on to attack at K13 and leaves them
        // a liberty shorter, so in the race it gains white nothing. Reported: missed
        String sgf = "(;SZ[13]AB[kc][jc][kd][ke][ib][hb][hc][fc][mb][mc]AW[le][ld][kf][jf][je][id][ic]"
                + "[jb][lb][kb][jd]AB[lc](;B[lf];W[lg](;B[ja]C[RIGHT])(;B[la]C[RIGHT])(;B[mf];W[mg]"
                + "(;B[ja]C[RIGHT])(;B[la]C[RIGHT])))(;B[ja];W[md])(;B[la];W[md])(;B[md];W[me]))";
        CollectionDetector.MatchResult result = detector.detect(parse(sgf));
        assertTrue(result.matches(), result.reason());
        assertEquals("black M8 is given up: white then needs 4 moves, not 2, to capture the 7 stones at N12",
                result.reason());
        assertFalse(another.detect(parse(sgf)).matches());
    }

    @Test
    void recognizesAStoneAddedToStonesAlreadyCutOff() throws Exception {
        String[] positions = {
                // 4500 and 44149: a second stone on the first line, beside a cutting stone
                PROBLEM_4500,
                "(;SZ[19]AW[ar][br][bq][bp][ap][co][do][eo][fp][fq][fr][fs][eq][dq][cn]AB"
                        + "[cp][dp][ep][cq][cr][bs][cs][dr][er][es][bo][fo][gp][go][hq][hr][hs](;B["
                        + "ao];W[bn](;B[gq];W[an](;B[gr]C[RIGHT])(;B[gs]C[RIGHT]))(;B[gr];W[an](;B["
                        + "gq]C[RIGHT])(;B[gs]C[RIGHT]))(;B[gs];W[an](;B[gq]C[RIGHT])(;B[gr]C[RIGHT"
                        + "])))(;B[gq];W[as])(;B[gr];W[as])(;B[gs];W[as])(;B[bn];W[bm])(;B[an];W[bn"
                        + "])(;B[bm];W[bn])(;B[en];W[bn]))",
                // 47961: black H12 joins three stones, two of them in atari; all four are given up
                "(;SZ[13]AB[kh][ic][hc][la][lb][mb][kb][jb][kc][kd][gb][je][jf][kf]AW[lc]"
                        + "[jd][id][hd][ib][ia][ja][fb][fc][gc][ld][le][ke][mc][lf][jc]AB[lg](;B[hb"
                        + "];W[ga](;B[mf]C[RIGHT])(;B[me]C[RIGHT])(;B[md]C[RIGHT]))(;B[mf];W[ka])(;"
                        + "B[me];W[ka])(;B[md];W[ka])(;B[ha];W[hb])(;B[ga];W[ka]))"
        };

        for (String sgf : positions) {
            CollectionDetector.MatchResult result = another.detect(parse(sgf));
            assertTrue(result.matches(), result.reason());
            assertTrue(result.reason().contains(" it joins at "), result.reason());

            // and the first collection says where it belongs
            CollectionDetector.MatchResult alone = detector.detect(parse(sgf));
            assertFalse(alone.matches(), alone.reason());
            assertTrue(alone.reason().contains("that is another-stone-for-liberty"), alone.reason());
        }
    }

    @Test
    void saysWhatTheAddedStoneIsGivenUpWith() throws Exception {
        assertEquals("black A18 is given up with the stone it joins at B18: white then needs 4 moves, not 2, "
                + "to capture the 6 stones at C17", another.detect(parse(PROBLEM_4500)).reason());
    }

    @Test
    void leavesALoneStoneToTheFirstCollection() throws Exception {
        CollectionDetector.MatchResult result = another.detect(parse(PROBLEM_43924));
        assertFalse(result.matches(), result.reason());
        assertEquals("black B5 is given up alone: that is stone-for-liberty, not this collection", result.reason());
    }

    @Test
    void namesTheSiteCollectionOfEach() {
        assertEquals("stone-for-liberty", detector.name());
        assertEquals(485, detector.siteCollection());
        assertEquals("another-stone-for-liberty", another.name());
        assertEquals(486, another.siteCollection());
    }

    @Test
    void leavesOutAThrowInThoughItGainsTheLibertyToo() throws Exception {
        String[] positions = {
                // 59677: after taking E1 white is still short of liberties and has to fill there.
                // Reported: it gains the liberty the same way, but is not what the collection is
                // about, where the stone has to be approached before it is taken
                "(;SZ[13]AB[bl][bm][al][bk][ck][dk][dj][ek][fk][fl]AW[cl][dl][el][ak][aj]"
                        + "[bj][cj][ci][di][ej][fj][gj][gk][gl][gm][fm]AB[ei][eh][dh][cg][bh](;B[em"
                        + "];W[dm](;B[bi];W[em](;B[ch];W[cm];B[ai]C[RIGHT])(;B[ai];W[cm];B[ch]C[RIG"
                        + "HT]))(;B[ch];W[em](;B[bi];W[cm];B[ai]C[RIGHT])(;B[ai];W[cm];B[bi]C[RIGHT"
                        + "]))(;B[ai];W[em](;B[bi];W[cm];B[ch]C[RIGHT])(;B[ch];W[cm];B[bi]C[RIGHT])"
                        + "))(;B[bi];W[cm])(;B[dm];W[em](;B[bi];W[cm])(;B[ch];W[cm])(;B[ai];W[cm]))"
                        + "(;B[cm];W[em](;B[bi];W[dm];B[ch];W[am])(;B[ch];W[dm])(;B[ai];W[dm]))(;B["
                        + "ch];W[cm])(;B[ai];W[cm]))",
                // 46988 and 46351: the thrown-in stone gives atari, and taking it answers that
                "(;SZ[13]AB[al][bl][bm][cl][dl][ek][fk][gk][fi][di][ci][bi]AW[dk][ck][bk]"
                        + "[ak][el][fl][gl][dm][hk][ik][il]AB[hl](;B[em];W[fm](;B[dj];W[hm](;B[aj]C"
                        + "[RIGHT])(;B[bj]C[RIGHT])(;B[cj]C[RIGHT]))(;B[cj];W[hm](;B[dj]C[RIGHT])(;"
                        + "B[bj]C[RIGHT])(;B[aj]C[RIGHT]))(;B[hm]C[RIGHT])(;B[bj];W[hm](;B[dj]C[RIG"
                        + "HT])(;B[cj]C[RIGHT])(;B[aj]C[RIGHT]))(;B[aj];W[hm](;B[dj]C[RIGHT])(;B[cj"
                        + "]C[RIGHT])(;B[bj]C[RIGHT])))(;B[dj];W[em])(;B[cj];W[em])(;B[bj];W[em])(;"
                        + "B[aj];W[em])(;B[hm];W[em]))",
                "(;SZ[19]AB[cc][ce][dg][cd][cb][ca][bc][db][eb][ea][df][fc][fd][gd][ff]AW"
                        + "[ec][gc][hd][bb][ac][ad][bd][be][bf][cf][fa][fb][hb][dc][dd][de][ee](;B["
                        + "ab];W[aa](;B[ef]C[RIGHT])(;B[fe]C[RIGHT])(;B[ed]C[RIGHT]))(;B[ef];W[ba])"
                        + "(;B[fe];W[ba])(;B[ed];W[ba])(;B[ba];W[ab])(;B[aa];W[ab]))"
        };

        for (String sgf : positions) {
            CollectionDetector.MatchResult result = detector.detect(parse(sgf));
            assertFalse(result.matches(), result.reason());
            assertTrue(result.reason().contains("throw-in"), result.reason());
            assertFalse(another.detect(parse(sgf)).matches());
        }
    }

    @Test
    void leavesOutTimeGainedForASingleStone() throws Exception {
        String[] positions = {
                // 47748: black C1 is the vital point of black's own eye space, where white answers
                // every other first move; the time it gains is for the one stone at A2, and C1 is
                // never taken in the line. Reported: not a match
                "(;SZ[13]AB[al][aj][bk][ck][dk][cj][el][em]AW[bm][ek][fk][fl][dj][di][ci][bi][bj](;B[cm]"
                        + ";W[cl](;B[dl]C[RIGHT])(;B[dm];W[bl])(;B[bl];W[dm]))(;B[cl];W[cm])(;B[bl];W[c"
                        + "m])(;B[dm];W[cm])(;B[dl];W[cm]))",
                // 51742: black K10 is where white's two halves join; the time it gains is for the
                // one stone at J13, which the line leaves on its last liberty with four more, for
                // white to take. Reported: not a match
                "(;SZ[13]AW[la][lb][kb][kc][kd][id][hd][hc][hb][ha][he]AB[ga][gb][gc][gd][ge][gf][hf][if"
                        + "][ie][ke][kf][ld][lc][mb][jb][ka][ia](;B[jd];W[jc](;B[ib];W[je](;B[ja]C[RIGH"
                        + "T])(;B[jf];W[ja])(;B[ic];W[ja])(;B[mc];W[ja]))(;B[ic];W[je])(;B[je];W[ja])(;"
                        + "B[ja];W[ib]))(;B[jc];W[jd])(;B[ic];W[jd])(;B[ib];W[jd])(;B[je];W[jd])(;B[mc]"
                        + ";W[jd]))"
        };

        for (String sgf : positions) {
            CollectionDetector.MatchResult result = detector.detect(parse(sgf));
            assertFalse(result.matches(), result.reason());
            assertFalse(another.detect(parse(sgf)).matches());
        }
        // 47748 is left out as that. 51742 fails a step earlier since moves that shorten the chain
        // being raced are not counted: white then need not capture K10 at all
        String reason = detector.detect(parse(positions[0])).reason();
        assertTrue(reason.contains("single stone"), reason);
    }

    @Test
    void rejectsAStoneBesideALibertyOfTheGroupItHelps() throws Exception {
        String[] positions = {
                // 52476: black E1 makes an eye of D1; it is never taken on its own
                "(;SZ[13]AW[bm][bl][bk][al][ck][dj][ej][ek][fk][fl][gm][hl][hk]AB[gl][gk]"
                        + "[gj][fj][ei][di][ch][cj][bj][dk][dl][cl][cm](;B[em];W[fm];B[aj];W[hm];B["
                        + "ak];W[el];B[am]C[RIGHT])(;B[el];W[fm])(;B[aj];W[el])(;B[fm];W[em])(;B[ak"
                        + "];W[aj];B[em];W[bi](;B[ci];W[fm];B[bh];W[hm])(;B[fm];W[ci]))(;B[hm];W[el"
                        + "]))",
                // 39405: black T18 makes a ko of T19
                "(;SZ[19]AB[ob][oc][od][oe][of][pa][pc][qd][ra][rd][re]AW[pb][pd][pe][pf]"
                        + "[pg][qb][qc][qe][qg][rb][rf][rh][sc](;B[sb](;W[se]C[CHOICE];B[qa];W[sa]C"
                        + "[RIGHT])(;W[sa]C[RIGHT]))(;B[qa];W[sb];B[sd];W[sf])(;B[rc];W[se])(;B[se]"
                        + ";W[sf]))"
        };

        for (String sgf : positions) {
            CollectionDetector.MatchResult result = detector.detect(parse(sgf));
            assertFalse(result.matches(), result.reason());
        }
        // 52476 is turned down as that. 39405 fails on the count already: white's atari at R19
        // shortens the stones black goes on to attack there, so it is no quick capture to begin with
        String reason = detector.detect(parse(positions[0])).reason();
        assertTrue(reason.contains("beside its own liberty"), reason);
    }

    @Test
    void rejectsAHaneThatCanConnect() throws Exception {
        // 48579: black H1 stops the approach as well, but it is a wall, connected at J1 if cut
        CollectionDetector.MatchResult result = detector.detect(parse("(;SZ[13]AW[bm][bl][bk][cj][dj][ej][ek][fl][fm][gl][hl]AB[dk][ck][cl][cm][dm][el]"
                + "[fk][gk][hk][ik][il](;B[hm]C[RIGHT])(;B[gm];W[hm]))"));
        assertFalse(result.matches(), result.reason());
    }

    @Test
    void rejectsAnAtariThatWinsByItself() throws Exception {
        String[] positions = {
                // 47160: the two white stones put in atari cannot be saved
                "(;SZ[13]AW[ma][mc][lc][kc][jb][ib][hc][gc][gb][gd]AB[ic][jc][hd][he][je]"
                        + "[le][kb][lb][mb][hb](;B[ja];W[ha](;B[kd];W[ia](;B[ld];W[ka];B[md]C[RIGHT"
                        + "])(;B[md];W[ka];B[ld]C[RIGHT]))(;B[la];W[ia])(;B[ld];W[ia](;B[kd]C[RIGHT"
                        + "])(;B[md]C[RIGHT]))(;B[md];W[ia](;B[kd]C[RIGHT])(;B[ld]C[RIGHT])))(;B[kd"
                        + "];W[ka])(;B[ka];W[ha])(;B[la];W[ia](;B[kd];W[ka])(;B[ld];W[ka])(;B[md];W"
                        + "[ka]))(;B[ld];W[ka])(;B[md];W[ka])(;B[ha];W[ga](;B[kd];W[ka])(;B[la];W[k"
                        + "a];B[ja];W[ia](;B[kd];W[ka])(;B[ld];W[ka])(;B[md];W[ka]))))",
                // 51091: taking the thrown-in stone leaves a ko
                "(;SZ[19]AW[bq][aq][cq][dq][dp][co][cn][cr][bs]AB[ar][ap][bp][cp][do][ep]"
                        + "[eq][fr][dr][es](;B[cs]C[RIGHT])(;B[br];W[as])(;B[ds];W[bo])(;B[an];W[bo"
                        + "]))"
        };

        for (String sgf : positions) {
            CollectionDetector.MatchResult result = detector.detect(parse(sgf));
            assertFalse(result.matches(), result.reason());
            assertTrue(result.reason().contains("atari"), result.reason());
        }
    }

    @Test
    void rejectsAStoneThatPutsTheGroupOutOfReach() throws Exception {
        // 57014: white can never fill the liberty again (the stones taken are taken back), which
        // is more than a liberty gained
        CollectionDetector.MatchResult result = detector.detect(parse("(;SZ[13]AB[al][dl][dk][cj][bj][ci][ai][ah][bh][fk]AW[ck][bk][bl][bm][dj][di][dh]"
                + "[ch][bg][ag](;B[ak];W[am](;B[cl];W[aj];B[ak]C[RIGHT])(;B[cm];W[aj];B[ak]C[RIGHT]"
                + "))(;B[cl];W[ak])(;B[cm];W[ak]))"));
        assertFalse(result.matches(), result.reason());
    }

    @Test
    void rejectsAThrowInThatGainsNoLiberty() throws Exception {
        // 45550: black E1 is given up to make white connect and die; black's own chain is no
        // further from capture for it
        CollectionDetector.MatchResult result = detector.detect(parse("(;SZ[19]AB[ar][br][cr][dr][eq][fq][gq][hr][hs]AW[er][fr][gr][bs][cs][ds][bq][cq]"
                + "[dq](;B[es];W[fs];B[gs]C[RIGHT])(;B[gs];W[aq])(;B[fs];W[aq]))"));
        assertFalse(result.matches(), result.reason());
    }

    @Test
    void rejectsAThrowInThatIsPlayedAgain() throws Exception {
        // 59633: white takes N12 at N13 and black throws in at N12 a second time
        CollectionDetector.MatchResult result = detector.detect(parse("(;SZ[13]AW[la][lb][mc][ld][kd][jd][id][hc][gc][gb][ha]AB[hb][ib][ic][jc][kc][lc][kb][ja]"
                + "(;B[mb];W[ma];B[ka];W[md];B[mb]C[RIGHT])(;B[md];W[mb];B[me];W[le])(;B[ka];W[mb]))"));
        assertFalse(result.matches(), result.reason());
        assertTrue(result.reason().contains("played again"), result.reason());

        // 46400: white takes B17 and black throws in there a second time, to connect and die. It
        // is turned down on the count already: with white's moves that shorten the stones black
        // goes on to capture left out, the throw-in gains only the move it costs
        result = detector.detect(parse("(;SZ[19]AW[cc][eb][ab][bb][ad][bd][dc][ec][da]AB[ae][fd][cb][ba][ea][fa][fb][fc]"
                + "[ed][dd][cd][be](;B[bc];W[ac](;B[ca];W[db];B[bc]C[RIGHT])(;B[aa];W[ca])(;B[db];W"
                + "[ca]))(;B[ac];W[bc])(;B[ca];W[aa](;B[bc];W[db])(;B[ac];W[bc])(;B[db];W[da]))(;B["
                + "aa];W[ca])(;B[db];W[ca]))"));
        assertFalse(result.matches(), result.reason());
    }

    @Test
    void rejectsOrdinaryMoves() throws Exception {
        String[] positions = {
                // 42272: a solid connection that fills a liberty
                "(;SZ[19]AW[bq][cq][cp][co][do][ep][eq][er][gp]AB[dp][dq][dr][br][eo][dn]"
                        + "[cn][bo][en](;B[cr]C[RIGHT])(;B[aq];W[cr])(;B[bp];W[cr])(;B[ar];W[cr]))",
                // 41734: a capture
                "(;SZ[19]AB[cr][dq][eq][fr][es]AW[er][dr][fq][gr];B[ds]C[RIGHT])"
        };

        for (String sgf : positions) {
            CollectionDetector.MatchResult result = detector.detect(parse(sgf));
            assertFalse(result.matches(), result.reason());
        }
    }

    @Test
    void rejectsTheSacrificeWhenItOnlyComesLater() throws Exception {
        // 52355: white gives up B5 the same way, but at move 5
        CollectionDetector.MatchResult result = detector.detect(parse("(;SZ[13]AB[al][bk][bj][ci][di][dj][dk][el][fl][gl][dm]AW[cj][ck][cl][bl][dl][ek]"
                + "[gk][fk][hk][hl](;W[bm];B[em];W[gm];B[ak];W[bi];B[bh](;W[hm];B[ai];W[fm]C[RIGHT]"
                + ")(;W[ai];B[ah];W[hm];B[aj](;W[fm]C[RIGHT])(;W[bi];B[ai];W[fm]C[RIGHT])))(;W[em];"
                + "B[bm])(;W[gm];B[bm])(;W[ak];B[bm]))"));
        assertFalse(result.matches(), result.reason());
    }

    @Test
    void rejectsAProblemThatCanAlsoBeSolvedWithoutTheSacrifice() throws Exception {
        // the position of 43924, with a tree that calls the direct move right as well
        Node node = parse("(;SZ[19]AB[ar][br][bs][cp][cq][dp][ep][fq][fr]AW[bq][bp][cr][cs][dr][dq][co][cn]"
                + "(;B[bo];W[bn];B[er]C[RIGHT])(;B[er]C[RIGHT]))");
        CollectionDetector.MatchResult result = detector.detect(node);
        assertFalse(result.matches(), result.reason());
    }

    @Test
    void rejectsATreeWithNoRightMove() throws Exception {
        Node node = parse("(;SZ[19]AB[ar][br][bs][cp][cq][dp][ep][fq][fr]AW[bq][bp][cr][cs][dr][dq][co][cn]"
                + "(;B[bo];W[bn])(;B[er];W[aq]))");
        assertFalse(detector.detect(node).matches());
    }

    private Node parse(String sgf) throws Exception {
        return new Parser().parse(sgf);
    }
}
