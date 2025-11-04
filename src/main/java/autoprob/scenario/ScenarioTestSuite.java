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
    private static final double LOSS_RANGE = 0.5;

    public ScenarioTestSuite(Properties props) {
        this.props = props;
    }

    private static final String invasion1 = "(;GM[1]FF[4]CA[UTF-8]AP[Drago:4.33]SZ[19]KM[9.5]AB[db][eb][nb][ob][hc][lc][qd][he][le][qe][ef][gf][if][pf][jg][lg][ch][eh][jh][kh][ph][pj][pk][ql][pm][qn][mo][oo][dp][gp][hp][ip][jp][np][op][dq][iq][kq][nq][pq][dr][or]AW[fb][hb][pb][cc][ec][fc][ic][jc][oc][qc][rc][dd][nd][pd][je][cf][jf][kf][lf][nf][kg][nh][nj][ok][ol][pl][mp][pp][qp][eq][gq][hq][jq][mq][er][ir][jr][kr][lr][nr][ms]PL[W])";

    public record ScenTest(String sgf, String path, double score, double loss, String responseMove, String rank) {}

    public void runSuite() throws Exception {
        ScenTest[] tests = {
                new ScenTest(invasion1, "B2", 6.5, 9.0, "C5", "ai"),
//                new ScenTest(invasion1, "C7", 2.5, 0.5),
        };

        KataBrain brain = new KataBrain(props);

        // iterate through tests
        int cnt = 0;
        for (ScenTest test : tests) {
            System.out.println("Running scenario test #" + (cnt));

            Analysis analysis = new Analysis(props, brain);
            try {
                var req = new AnalysisRequest();
                req.scenario = new AnalysisRequest.Scenario();
                req.scenario.sgf = test.sgf;
                req.difficulty = test.rank;
                req.path = test.path;

                AnalysisResult[] results = analysis.analyze(req);
                AnalysisResult result = results[0]; // base move
                if (result.rank.equals(test.rank)) {
                    System.out.println("Test passed for expected rank: " + test.rank);
                } else {
                    System.out.println("Test failed for expected rank: " + test.rank +
                            ", got: " + result.rank);
                }
                double resultScore = result.score;
                if (Math.abs(resultScore - test.score) < SCORE_RANGE) {
                    System.out.println("Test passed for expected score: " + df.format(test.score));
                } else {
                    System.out.println("Test failed for expected score: " + df.format(test.score) +
                            ", got: " + df.format(resultScore));
                }

                double resultLoss = result.loss;
                if (Math.abs(resultLoss - test.loss) < LOSS_RANGE) {
                    System.out.println("Test passed for expected loss: " + df.format(test.loss));
                } else {
                    System.out.println("Test failed for expected loss: " + df.format(test.loss) +
                            ", got: " + df.format(resultLoss));
                }

                // verify response move if in test
                if (test.responseMove != null && !test.responseMove.isEmpty()) {
                    AnalysisResult response = results[1]; // response move
                    String move = response.path.substring(response.path.lastIndexOf(',') + 1);
                    if (move.equals(test.responseMove)) {
                        System.out.println("Test passed for expected response move: " + move);
                    } else {
                        System.out.println("Test failed for expected response move: " + test.responseMove +
                                ", got: " + move);
                    }
                }
            } catch (Exception e) {
                System.out.println("Exception during test for expected score: " + df.format(test.score));
                e.printStackTrace();
            }
            cnt++;
        }

        brain.stopKataBrain();
    }
}
