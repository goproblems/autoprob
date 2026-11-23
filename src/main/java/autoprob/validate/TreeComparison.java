package autoprob.validate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// compares the generated solution tree with the author's correct tree. both are given
// as their RIGHT lines (move lists from the problem root). positions are compared where
// both trees reach them: a working solver move the author has and the tree lacks is the
// most serious gap, since on the site it marks a correct answer wrong.
public class TreeComparison {
    public int authorLines;
    public int generatedLines;
    public int reproduced;     // author lines the generated tree contains exactly
    public int authorNodes;    // moves in the author's correct tree
    public int generatedNodes; // moves in the generated tree
    public int matchedNodes;   // moves in both
    // entries read "<position>: <move>", the position as moves from the root or "root"
    public List<String> missingSolverMoves = new ArrayList<>(); // author's working moves the tree lacks
    public List<String> extraSolverMoves = new ArrayList<>();   // tree's working moves the author lacks
    public List<String> missingResponses = new ArrayList<>();   // author's resistances the tree lacks
    public List<String> extraResponses = new ArrayList<>();     // tree's resistances the author lacks
    public List<String> endsEarly = new ArrayList<>(); // the tree ends where the author continues
    public List<String> endsLate = new ArrayList<>();  // the tree continues where the author ends

    private static class Tree {
        final Map<String, Set<String>> next = new LinkedHashMap<>();
        final Map<String, Integer> depth = new HashMap<>();
        final Set<String> nodes = new HashSet<>();

        Tree(List<List<String>> lines) {
            for (List<String> line : lines) {
                for (int i = 0; i <= line.size(); i++) {
                    String key = DecisionRecord.key(line.subList(0, i));
                    depth.put(key, i);
                    if (i > 0) nodes.add(key);
                    next.computeIfAbsent(key, k -> new LinkedHashSet<>())
                            .add(i < line.size() ? line.get(i) : DecisionRecord.END);
                }
            }
        }
    }

    public static TreeComparison compare(List<List<String>> generated, List<List<String>> author) {
        TreeComparison tc = new TreeComparison();
        Tree gen = new Tree(generated), auth = new Tree(author);
        tc.authorLines = author.size();
        tc.generatedLines = generated.size();
        Set<String> genLines = new HashSet<>();
        for (List<String> line : generated) genLines.add(String.join(" ", line));
        for (List<String> line : author) {
            if (genLines.contains(String.join(" ", line))) tc.reproduced++;
        }
        tc.authorNodes = auth.nodes.size();
        tc.generatedNodes = gen.nodes.size();
        for (String n : auth.nodes) {
            if (gen.nodes.contains(n)) tc.matchedNodes++;
        }

        for (Map.Entry<String, Set<String>> e : auth.next.entrySet()) {
            Set<String> g = gen.next.get(e.getKey());
            if (g == null) continue; // the generated tree never reached this position
            Set<String> a = e.getValue();
            boolean solverTurn = auth.depth.get(e.getKey()) % 2 == 0;
            for (String m : a) {
                if (m.equals(DecisionRecord.END) || g.contains(m)) continue;
                (solverTurn ? tc.missingSolverMoves : tc.missingResponses).add(e.getKey() + ": " + m);
            }
            for (String m : g) {
                if (m.equals(DecisionRecord.END) || a.contains(m)) continue;
                (solverTurn ? tc.extraSolverMoves : tc.extraResponses).add(e.getKey() + ": " + m);
            }
            boolean aEnds = a.contains(DecisionRecord.END), gEnds = g.contains(DecisionRecord.END);
            if (aEnds && !gEnds) tc.endsLate.add(e.getKey());
            if (gEnds && !aEnds) tc.endsEarly.add(e.getKey());
        }
        return tc;
    }

    public String summary() {
        return "author lines " + authorLines + " (" + reproduced + " reproduced), generated lines " + generatedLines
                + "; solver moves missing " + missingSolverMoves.size() + ", extra " + extraSolverMoves.size()
                + "; responses missing " + missingResponses.size() + ", extra " + extraResponses.size()
                + "; ends early " + endsEarly.size() + ", late " + endsLate.size();
    }
}
