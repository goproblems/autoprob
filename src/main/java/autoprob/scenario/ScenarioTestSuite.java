package autoprob.scenario;

import autoprob.KataBrain;
import autoprob.api.AnalysisRequest;
import autoprob.api.AnalysisResult;

import java.text.DecimalFormat;
import java.util.Properties;

public class ScenarioTestSuite {
    private final Properties props;
    private static final DecimalFormat df = new DecimalFormat("0.00");

    private static final double SCORE_RANGE = 0.5;

    public ScenarioTestSuite(Properties props) {
        this.props = props;
    }

    private static final String invasion1 = "(;GM[1]FF[4]CA[UTF-8]AP[Drago:4.33]SZ[19]KM[9.5]AB[db][eb][nb][ob][hc][lc][qd][he][le][qe][ef][gf][if][pf][jg][lg][ch][eh][jh][kh][ph][pj][pk][ql][pm][qn][mo][oo][dp][gp][hp][ip][jp][np][op][dq][iq][kq][nq][pq][dr][or]AW[fb][hb][pb][cc][ec][fc][ic][jc][oc][qc][rc][dd][nd][pd][je][cf][jf][kf][lf][nf][kg][nh][nj][ok][ol][pl][mp][pp][qp][eq][gq][hq][jq][mq][er][ir][jr][kr][lr][nr][ms]PL[W])";

    public record ScenTest(String sgf, double score) {}

    public void runSuite() throws Exception {
        ScenTest[] tests = { new ScenTest(invasion1, 2.0) };

        KataBrain brain = new KataBrain(props);

        // iterate through tests
        for (ScenTest test : tests) {
            try (Analysis analysis = new Analysis(props, brain)) {
                var req = new AnalysisRequest();
                req.scenario = new AnalysisRequest.Scenario();
                req.scenario.sgf = test.sgf;
                req.difficulty = "ai";

                AnalysisResult result = analysis.analyze(req);
                double resultScore = result.score;
                if (Math.abs(resultScore - test.score) < SCORE_RANGE) {
                    System.out.println("Test passed for expected score: " + df.format(test.score));
                } else {
                    System.out.println("Test failed for expected score: " + df.format(test.score) +
                            ", got: " + df.format(resultScore));
                }
            } catch (Exception e) {
                System.out.println("Exception during test for expected score: " + df.format(test.score));
                e.printStackTrace();
            }
        }
    }
}
