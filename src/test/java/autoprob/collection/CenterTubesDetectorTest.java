package autoprob.collection;

import autoprob.go.Node;
import autoprob.go.parse.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CenterTubesDetectorTest {
    private final CenterTubesDetector detector = new CenterTubesDetector();

    @Test
    void recognizesTheEightCollectionPositionsThatFitTheStrictClearanceRule() throws Exception {
        String[] positions = {
                "(;SZ[13]AB[dj][dk][di][dh][eg][fg][fh][fi][fj][fk][ek]AW[el][dl][fl][gj][gk][gi][gh][gg][ff][ef][df][cg][ci][cj][ch][ck])",
                "(;SZ[13]AB[di][ej][fj][gj][eh][fh][gh][hi]AW[cj][ci][ch][dg][eg][fg][gg][hg][ih][ii][ij][dk][ek][fk][gk][hk])",
                "(;SZ[13]AB[di][ej][fj][gj][eh][fh][gh][hi]AW[ei][gi][ch][ci][cj][dk][ek][fk][gk][hk][ij][ii][ih][dg][eg][fg][gg][hg])",
                "(;SZ[19]AB[hh][hi][hj][hk][hl][hm][hn][in][jn][jm][jk][jj][ji]AW[jh][jg][ig][hg][gh][gi][gj][gk][gl][gm][gn][ho][io][jo][kn][km][kl][kk][kj][ki][ik])",
                "(;SZ[19]AB[hh][hi][hj][hk][hl][hm][hn][jn][jm][jl][jk][jj][ji][ih]AW[gh][gi][gj][gk][gl][gm][gn][io][ho][jo][kn][km][kl][kk][kj][ki][jh][ig][hg][jg][ii][ij][il])",
                "(;SZ[19]AW[ji][jk][in][jn][kn][ll][lm][lk][lj][li][lh][lg][kf][jf][if][hg][hh][hi][hj][hk][hl][hm]AB[il][ik][ij][ii][ig][ih][kg][kh][ki][kj][kk][kl][km][im])",
                "(;SZ[19]AW[ji][jk][in][jn][kn][ll][lm][lk][lj][li][lh][lg][hg][hh][hi][hj][hk][hl][hm][jj][lf][ke][je][ie][hf]AB[il][ik][ij][ii][ig][ih][kg][kh][ki][kj][kk][kl][km][im][if][kf])",
                "(;SZ[19]AB[ig][ih][ii][ij][ik][il][im][if][kf][kg][ji][ki][kj][kk][kl][km]AW[in][jn][kn][lm][ll][lk][lj][li][lh][lg][lf][ke][je][ie][hf][hg][hh][hi][hj][hk][hl][hm])"
        };

        for (String sgf : positions) {
            CollectionDetector.MatchResult result = detector.detect(parse(sgf));
            assertTrue(result.matches(), result.reason());
        }
    }

    @Test
    void rejectsLegacyCollectionPositionsWithOnlyTwoLinesOfClearance() throws Exception {
        String[] positions = {
                "(;SZ[19]AB[md][me][nc][ne][oc][oe][pc][pe][qd][qe]AW[lb][ld][le][mc][mf][nb][nf][ob][of][pb][pf][qc][qf][rb][rd][re])",
                "(;SZ[19]AB[dp][dq][eq][fq][gq][hq][eo][fo][go][ho][io][jp]AW[iq][jo][kp][hr][gr][fr][er][dr][cq][cp][do][en][fn][gn][hn][in][dn][kq][jr])",
                "(;SZ[19]AB[cq][dq][eq][ep][eo][en][cp][co][cn][cm][dl][em]AW[do][bq][cr][dr][er][fq][fp][fo][fn][fm][fl][ek][ck][bl][bm][bn][bo][bp][dk])",
                "(;SZ[19]AB[qq][pq][oq][op][oo][on][om][pm][qm][qn][qo][qp]AW[or][pr][qr][rq][rp][ro][rn][rm][ql][pl][ol][nm][nn][no][np][nq])",
                "(;SZ[13]AW[bk][bj][cl][dl][el][fl][gl][hk][ik][ij][ii][hh][gh][fh][eh][dh][ch][ci][ej]AB[cj][ck][dk][ek][fk][gk][hj][hi][gi][fi][ei][di])"
        };

        for (String sgf : positions) {
            CollectionDetector.MatchResult result = detector.detect(parse(sgf));
            assertFalse(result.matches(), result.reason());
        }
    }

    @Test
    void rejectsCollectionProblem4002BecauseNeitherWholeColorFits() throws Exception {
        Node node = parse("(;SZ[19]AW[ij][ii][ik][hk][ji][gk][fk][ek]AB[gj]AW[hi][gi][fi][ei][di][dj]"
                + "AB[cj][ci][dh][eh][fh][gh][hh][ih][ck]AW[dk]AB[dl][el][fl][gl][hl][il][jj][jk]"
                + "AW[jl][kl][ll][ml][nl]AB[ki][li][mi][jh]AW[kh][mh][lh][nh]AB[kk][lk][mk][nk][nj][ni]AW[ok][oj][oi])");
        assertFalse(detector.detect(node).matches());
    }

    @Test
    void rejectsThreeByXShapeAlongBoardEdge() throws Exception {
        Node node = parse("(;SZ[9]AB[aa][ba][ca][ab][bb][cb][ac][bc][cc][ad][bd][cd])");
        assertFalse(detector.detect(node).matches());
    }

    @Test
    void rejectsAnOutlyingStoneOfTheSameColor() throws Exception {
        Node node = parse("(;SZ[9]AB[dd][ed][fd][de][ee][fe][df][ef][ff][dg][eg][fg][bb])");
        assertFalse(detector.detect(node).matches());
    }

    @Test
    void rejectsTubeShorterThanFiveOnItsLongSide() throws Exception {
        Node node = parse("(;SZ[9]AB[dd][ed][fd][de][ee][fe][df][ef][ff][dg][eg][fg]"
                + "AW[cd][gd][ce][ge][cf][gf][cg][gg][dc][ec][fc][dh][eh][fh])");
        assertFalse(detector.detect(node).matches());
    }

    @Test
    void rejectsReportedFalsePositives() throws Exception {
        String[] positions = {
                "(;SZ[13]AB[ec][ic][hd][ge][fe][fd][fc][gc][hc]AW[jc][jb][id][je][dc][db][he][hf][gf][ff][ee][ed])",
                "(;SZ[19]AW[pe][pf][qg][qh][oi][ri][oj][pj][rj][sk][rl][sl][rm]AB[pg][ph][rh][pi][qi][qj][pk][qk][rk][pl])",
                "(;SZ[19]AW[hh][lh][hi][ji][li][lj]AB[kg][lg][mg][mh][mi][mj][kk][lk][mk])",
                "(;SZ[19]AW[qa][ra][rb][rc][rd][qe][qf][pf][of][nf][me][le][ld][lc][lb][mb]AB[nb][mc][md][ne][nd][oe][pe][qd][qc][qb][pc][pa]AW[od][ob])",
                "(;SZ[13]AB[dk][dl][el][cj][bj][bi][bh][cg][cf][de][dd][ed][fd][ge][gf][gg][gh][ih][ii][ij][ik][hl][gl][fm]AW[ek][fk][di][ci][fi][hi][hj][df][dg][eg][fg][ff][fe][ee])",
                "(;SZ[13]AW[id][ic][hc][gc][fd][ee][dg][eg][fh][gh][hh][ig][jg][jf][je][df]AB[ie][if][hd][gd][gf][ge][gg][hg][fg][fe][ef][dc])"
        };

        for (String sgf : positions) {
            CollectionDetector.MatchResult result = detector.detect(parse(sgf));
            assertFalse(result.matches(), result.reason());
        }
    }

    private Node parse(String sgf) throws Exception {
        return new Parser().parse(sgf);
    }
}
