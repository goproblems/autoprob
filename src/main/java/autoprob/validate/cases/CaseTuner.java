package autoprob.validate.cases;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import autoprob.validate.ChoiceParams;
import autoprob.validate.ChoiceRule;
import autoprob.validate.DecisionRecord;

// cmd=cases-tune: tries alternative choice settings against the case library offline.
// the engine's numbers for every decision are already stored, so re-choosing under a
// grid of settings takes seconds. pass the values to try as tune.<setting>,
// e.g. tune.threat_stones=3,4,5 tune.response_criterion=testing,human. settings not
// named keep their current config values.
//
// caveats: the candidate list was gathered under the recording settings, so gathering
// limits can only narrow it; and the numbers come from one engine run each, so confirm a
// promising setting with a real replay (cmd=cases) before adopting it.
public class CaseTuner {
    private final Properties props;

    public CaseTuner(Properties props) {
        this.props = props;
    }

    private static class Outcome {
        String settings;
        int pass;
        List<String> fixed = new ArrayList<>();
        List<String> broken = new ArrayList<>();
    }

    public void run() throws Exception {
        CaseFeatures features = CaseFeatures.load(props);
        List<CaseFeatures.Item> items = new ArrayList<>(features.items);
        if (items.isEmpty()) {
            System.out.println("no decision cases with numbers; run cmd=cases first");
            return;
        }

        Map<String, List<String>> grid = new TreeMap<>();
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("tune.")) {
                grid.put(name.substring(5), Arrays.asList(props.getProperty(name).split(",")));
            }
        }
        ChoiceParams base = ChoiceParams.fromProps(props);
        boolean[] basePass = new boolean[items.size()];
        int baseCount = 0;
        for (int i = 0; i < items.size(); i++) {
            basePass[i] = passes(items.get(i), base);
            if (basePass[i]) baseCount++;
        }

        System.out.println("tuning over " + items.size() + " decision cases"
                + (features.resultsId != null ? " (numbers from replay " + features.resultsId + " where available)" : "")
                + (features.missing > 0 ? ", " + features.missing + " cases skipped without numbers" : ""));
        System.out.println("current settings pass " + baseCount + "/" + items.size());
        if (grid.isEmpty()) {
            System.out.println("no tune.<setting>=values given; e.g. tune.threat_stones=3,4,5 tune.severe_threat_ratio=0.5,0.6,0.8");
            return;
        }

        List<Outcome> outcomes = new ArrayList<>();
        List<String> names = new ArrayList<>(grid.keySet());
        for (Map<String, String> combo : combinations(names, grid)) {
            ChoiceParams p = base.copy();
            for (Map.Entry<String, String> e : combo.entrySet()) p.set(e.getKey(), e.getValue().trim());
            Outcome o = new Outcome();
            o.settings = combo.toString();
            for (int i = 0; i < items.size(); i++) {
                boolean pass = passes(items.get(i), p);
                if (pass) o.pass++;
                String id = items.get(i).file.problem + " @ " + items.get(i).c.key();
                if (pass && !basePass[i]) o.fixed.add(id);
                if (!pass && basePass[i]) o.broken.add(id);
            }
            outcomes.add(o);
        }
        outcomes.sort((a, b) -> a.pass != b.pass ? b.pass - a.pass
                : (a.fixed.size() + a.broken.size()) - (b.fixed.size() + b.broken.size()));

        int show = Integer.parseInt(props.getProperty("tune.show", "15"));
        System.out.println();
        System.out.println(String.format("%-6s %-6s %-6s  %s", "pass", "fixed", "broken", "settings"));
        for (int i = 0; i < Math.min(show, outcomes.size()); i++) {
            Outcome o = outcomes.get(i);
            System.out.println(String.format("%-6d %-6d %-6d  %s", o.pass, o.fixed.size(), o.broken.size(), o.settings));
        }
        Outcome best = outcomes.get(0);
        System.out.println();
        System.out.println("best: " + best.settings + " passes " + best.pass + "/" + items.size()
                + " (current " + baseCount + ")");
        for (String s : best.broken) System.out.println("  broken " + s);
        for (String s : best.fixed) System.out.println("  fixed  " + s);
    }

    private static boolean passes(CaseFeatures.Item item, ChoiceParams p) {
        List<Point> stakes = CaseExporter.stakes(item.c);
        DecisionRecord d = item.decision.copyAt(item.decision.at);
        ChoiceRule.Choice choice = ChoiceRule.decide(d, p, stakes);
        d.chose = choice.move;
        d.alternatives = choice.also;
        return item.c.passes(d);
    }

    private static List<Map<String, String>> combinations(List<String> names, Map<String, List<String>> grid) {
        List<Map<String, String>> out = new ArrayList<>();
        out.add(new LinkedHashMap<>());
        for (String name : names) {
            List<Map<String, String>> next = new ArrayList<>();
            for (Map<String, String> partial : out) {
                for (String value : grid.get(name)) {
                    Map<String, String> m = new LinkedHashMap<>(partial);
                    m.put(name, value);
                    next.add(m);
                }
            }
            out = next;
        }
        return out;
    }
}
