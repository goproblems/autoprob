package autoprob.validate;

import java.util.List;

// the author's own correct lines from a position, for a found move's line there that follows
// them (SolutionPathGenerator.setAuthorLines): at a ko a line has to show the ko its move
// comes to, and that is the author's own ko, or the move is not the same answer. lines are
// moves with color ("B T18"), each starting with the solver's move at the position
final class AuthorLine {
    private AuthorLine() {
    }

    // the author's line a found move's line follows: the shortest of the author's lines that
    // has the move as one of the solver's (the found move is then the author's own, played
    // sooner), else the shortest of them all. empty when the author has none
    static List<String> follow(List<List<String>> lines, String first) {
        List<String> best = null;
        boolean has = false;
        for (List<String> line : lines) {
            boolean mine = solverPlays(line, first);
            if (best == null || (mine && !has) || (mine == has && line.size() < best.size())) {
                best = line;
                has = mine;
            }
        }
        return best == null ? List.of() : best;
    }

    private static boolean solverPlays(List<String> line, String move) {
        for (int i = 0; i < line.size(); i += 2) {
            if (line.get(i).equals(move)) return true;
        }
        return false;
    }

    // the author's next move for the solver: the first of the solver's moves on the line that
    // the moves played so far do not have. null when every one of them is played
    static String next(List<String> line, List<String> at) {
        for (int i = 0; i < line.size(); i += 2) {
            if (!at.contains(line.get(i))) return line.get(i);
        }
        return null;
    }

    // whether the solver has played every move of its own on the line: it is played out
    static boolean done(List<String> line, List<String> at) {
        return !line.isEmpty() && next(line, at) == null;
    }

    // the author's answer to the solver's last move, on any of the lines: the move after it
    // there. null when the author has the move nowhere, or with nothing after it, or the answer
    // is played already
    static String answer(List<List<String>> lines, List<String> at) {
        if (at.isEmpty()) return null;
        String last = at.get(at.size() - 1);
        for (List<String> line : lines) {
            for (int i = 0; i + 1 < line.size(); i += 2) {
                if (line.get(i).equals(last) && !at.contains(line.get(i + 1))) return line.get(i + 1);
            }
        }
        return null;
    }
}
