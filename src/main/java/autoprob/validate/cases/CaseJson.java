package autoprob.validate.cases;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import autoprob.validate.IsolationRecord;
import autoprob.validate.RunRecorder;

// writes a case file compactly. gson's pretty printing puts every list item on its own
// line and every case carried its own copy of the isolation, so case files were mostly
// repetition and line breaks. here:
// - the isolation the cases share is stored once for the file (see CaseFile.isolation)
// - fractional numbers are rounded to 3 decimals, far finer than the noise between
//   engine runs
// - ownership deltas of 0 are left out: they never count toward a threat
// - lists and objects of plain values stay on one line, except the board rows, which
//   read as a board one row per line
// the output is still indented with one field per line, so diffs stay readable, and it is
// plain json: CaseStore reads it with gson like before.
final class CaseJson {
    private static final Set<String> ONE_PER_LINE = Set.of("rows");

    private CaseJson() {
    }

    // sets cf.isolation to the isolation most of its cases share
    static String write(CaseFile cf) {
        cf.isolation = mostCommonIsolation(cf.decisions);
        JsonObject root = RunRecorder.GSON.toJsonTree(cf).getAsJsonObject();
        JsonElement shared = root.get("isolation");
        if (shared != null) {
            for (JsonElement c : root.getAsJsonArray("decisions")) {
                JsonObject o = c.getAsJsonObject();
                if (shared.equals(o.get("isolation"))) o.remove("isolation");
            }
        }
        StringBuilder sb = new StringBuilder();
        value(sb, root, null, 0);
        return sb.toString();
    }

    private static IsolationRecord mostCommonIsolation(List<DecisionCase> cases) {
        Map<String, Integer> counts = new HashMap<>();
        IsolationRecord best = null;
        int bestCount = 0;
        for (DecisionCase c : cases) {
            if (c.isolation == null) continue;
            int n = counts.merge(RunRecorder.GSON.toJson(c.isolation), 1, Integer::sum);
            if (n > bestCount) {
                bestCount = n;
                best = c.isolation;
            }
        }
        return best;
    }

    private static void value(StringBuilder sb, JsonElement e, String key, int depth) {
        if (e.isJsonArray()) {
            array(sb, e.getAsJsonArray(), key, depth);
        } else if (e.isJsonObject()) {
            object(sb, e.getAsJsonObject(), key, depth);
        } else if (e.isJsonNull()) {
            sb.append("null");
        } else if (e.getAsJsonPrimitive().isNumber()) {
            sb.append(number(e.getAsNumber()));
        } else {
            sb.append(RunRecorder.GSON.toJson(e)); // strings escaped the way gson writes them
        }
    }

    private static void array(StringBuilder sb, JsonArray a, String key, int depth) {
        List<JsonElement> items = new ArrayList<>();
        a.forEach(items::add);
        if (items.isEmpty()) {
            sb.append("[]");
        } else if ((key == null || !ONE_PER_LINE.contains(key)) && items.stream().allMatch(CaseJson::plain)) {
            sb.append('[');
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(", ");
                value(sb, items.get(i), null, depth + 1);
            }
            sb.append(']');
        } else {
            sb.append("[\n");
            for (int i = 0; i < items.size(); i++) {
                indent(sb, depth + 1);
                value(sb, items.get(i), null, depth + 1);
                sb.append(i < items.size() - 1 ? ",\n" : "\n");
            }
            indent(sb, depth);
            sb.append(']');
        }
    }

    private static void object(StringBuilder sb, JsonObject o, String key, int depth) {
        List<Map.Entry<String, JsonElement>> members = new ArrayList<>();
        for (Map.Entry<String, JsonElement> m : o.entrySet()) {
            JsonElement v = m.getValue();
            // an ownership delta of 0 never counts toward a threat
            if ("deltas".equals(key) && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()
                    && number(v.getAsNumber()).equals("0")) continue;
            members.add(m);
        }
        if (members.isEmpty()) {
            sb.append("{}");
        } else if (depth > 0 && members.stream().allMatch(m -> plain(m.getValue()))) {
            sb.append('{');
            for (int i = 0; i < members.size(); i++) {
                if (i > 0) sb.append(", ");
                name(sb, members.get(i).getKey());
                value(sb, members.get(i).getValue(), members.get(i).getKey(), depth + 1);
            }
            sb.append('}');
        } else {
            sb.append("{\n");
            for (int i = 0; i < members.size(); i++) {
                indent(sb, depth + 1);
                name(sb, members.get(i).getKey());
                value(sb, members.get(i).getValue(), members.get(i).getKey(), depth + 1);
                sb.append(i < members.size() - 1 ? ",\n" : "\n");
            }
            indent(sb, depth);
            sb.append('}');
        }
    }

    // fractional numbers rounded to 3 decimals, without trailing zeros; whole numbers as is
    static String number(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
                || n instanceof BigInteger) {
            return n.toString();
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException(d + " is not a valid json number");
        }
        return BigDecimal.valueOf(d).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static boolean plain(JsonElement e) {
        return e.isJsonPrimitive() || e.isJsonNull();
    }

    private static void name(StringBuilder sb, String name) {
        sb.append(RunRecorder.GSON.toJson(new JsonPrimitive(name))).append(": ");
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
    }
}
