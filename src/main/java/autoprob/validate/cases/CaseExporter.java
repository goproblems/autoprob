package autoprob.validate.cases;

import java.awt.Point;
import java.io.PrintWriter;
import java.util.List;
import java.util.Properties;

import autoprob.go.Intersection;
import autoprob.validate.CandidateFeatures;
import autoprob.validate.ChoiceParams;
import autoprob.validate.ChoiceRule;
import autoprob.validate.DecisionRecord;

// cmd=cases-export: one csv row per candidate per decision case, labeled by what the
// case expects, for analysis in a spreadsheet or notebook. opponent decisions also get
// an END row carrying the decision's largest threat, since "should the line end here"
// is mostly a question about that number.
public class CaseExporter {
    private static final String HEADER = "problem,position,depth,role,source,tags,expect_choose,expect_avoid,"
            + "passes,candidate,cand_source,label,algo_chose,prior,visits,score_lead,human_policy,threat,"
            + "answer,answer_prior,human_answer,stake_dist,features_from";

    private final Properties props;

    public CaseExporter(Properties props) {
        this.props = props;
    }

    public void run() throws Exception {
        CaseFeatures features = CaseFeatures.load(props);
        ChoiceParams params = ChoiceParams.fromProps(props);
        String out = props.getProperty("out", "cases_export.csv");
        int rows = 0;
        try (PrintWriter w = new PrintWriter(out)) {
            w.println(HEADER);
            for (CaseFeatures.Item item : features.items) {
                DecisionRecord d = item.decision;
                DecisionCase c = item.c;
                List<Point> stakes = stakes(c);
                String prefix = csv(item.file.problem) + "," + csv(c.key()) + "," + c.at.size() + "," + c.role
                        + "," + c.source + "," + csv(String.join(" ", c.tags)) + "," + csv(String.join(" ", c.expect.choose))
                        + "," + csv(String.join(" ", c.expect.avoid)) + "," + c.passes(d.chose);
                int maxThreat = 0;
                for (CandidateFeatures cand : d.candidates) {
                    Integer threat = null;
                    if (c.role.equals(DecisionRecord.OPPONENT)) {
                        threat = ChoiceRule.countThreat(cand, params, stakes);
                        maxThreat = Math.max(maxThreat, threat);
                    }
                    w.println(prefix + "," + csv(cand.move) + "," + cand.source + "," + label(c, cand.move)
                            + "," + cand.move.equals(d.chose) + "," + num(cand.prior) + "," + num(cand.visits)
                            + "," + num(cand.scoreLead) + "," + num(cand.humanPolicy) + "," + num(threat)
                            + "," + csv(cand.answer) + "," + num(cand.answerPrior) + "," + num(cand.humanAnswer)
                            + "," + num(cand.stakeDist) + "," + item.from);
                    rows++;
                }
                if (c.role.equals(DecisionRecord.OPPONENT)) {
                    w.println(prefix + "," + DecisionRecord.END + ",," + label(c, DecisionRecord.END) + ","
                            + DecisionRecord.END.equals(d.chose) + ",,,,," + maxThreat + ",,,,," + item.from);
                    rows++;
                }
            }
        }
        System.out.println("wrote " + rows + " rows for " + features.items.size() + " cases to " + out
                + " (numbers from " + (features.resultsId != null ? "replay " + features.resultsId + " where available, else " : "")
                + "the recorded observation)");
        if (features.missing > 0) {
            System.out.println(features.missing + " cases have no numbers yet; run cmd=cases to replay them");
        }
    }

    static List<Point> stakes(DecisionCase c) {
        return c.isolation.stakes.stream().map(Intersection::gtp2point).toList();
    }

    private static String label(DecisionCase c, String move) {
        if (c.expect.choose.contains(move)) return "choose";
        if (c.expect.avoid.contains(move)) return "avoid";
        return "";
    }

    private static String num(Number n) {
        if (n == null) return "";
        if (n instanceof Double d) return String.format("%.4f", d);
        return n.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
}
