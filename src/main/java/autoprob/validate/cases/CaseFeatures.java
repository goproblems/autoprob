package autoprob.validate.cases;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import autoprob.validate.DecisionRecord;

// pairs each decision case with the candidate numbers to analyze: the fresh numbers
// from the latest replay when there is one, otherwise what was observed when the case
// was recorded. used by export and tune, which never touch the engine.
public class CaseFeatures {
    public static class Item {
        public CaseFile file;
        public DecisionCase c;
        public DecisionRecord decision;
        public String from; // replay id, or "observed"
    }

    public final List<Item> items = new ArrayList<>();
    public int missing; // cases with no numbers yet (author cases never replayed)
    public int moot;    // cases below a position where the line must end, left out
    public String resultsId;

    public static CaseFeatures load(Properties props) throws Exception {
        CaseFeatures cf = new CaseFeatures();
        List<CaseFile> files = new CaseStore(props).loadAll(props.getProperty("problem"));
        String which = props.getProperty("results", "latest");
        CaseReplayer.Results results = null;
        if (!which.equals("none")) {
            File dir = new File(props.getProperty("cases.results.dir", "cases_results"));
            results = which.equals("latest") ? CaseReplayer.latest(dir)
                    : CaseReplayer.read(new File(dir, which.endsWith(".json") ? which : which + ".json"));
        }
        Map<String, DecisionRecord> fresh = new HashMap<>();
        if (results != null) {
            cf.resultsId = results.id;
            for (CaseReplayer.CaseResult r : results.decisions) {
                if (r.decision != null) fresh.put(r.id(), r.decision);
            }
        }
        String sourceFilter = props.getProperty("source");
        for (CaseFile file : files) {
            for (DecisionCase c : file.decisions) {
                if (sourceFilter != null && !sourceFilter.equals(c.source)) continue;
                if (file.moot(c)) {
                    cf.moot++;
                    continue;
                }
                Item item = new Item();
                item.file = file;
                item.c = c;
                DecisionRecord d = fresh.get(file.problem + " @ " + c.key());
                if (d != null) {
                    item.decision = d;
                    item.from = results.id;
                } else if (c.observed != null && c.observed.decision != null) {
                    item.decision = c.observed.decision;
                    item.from = "observed";
                } else {
                    cf.missing++;
                    continue;
                }
                cf.items.add(item);
            }
        }
        return cf;
    }
}
