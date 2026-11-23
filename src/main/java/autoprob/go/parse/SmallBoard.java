package autoprob.go.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// a problem on a board smaller than 19x19 (goproblems has many on 13x13) moved into the
// matching corner of a full board, since everything here plays on 19x19: each axis keeps the
// edge its stones are nearer, and the far edge goes. a problem that comes near both edges of
// an axis cannot move without changing, so it is refused.
public class SmallBoard {
    // the lost edge has to be at least this many lines clear of every stone and move
    static final int CLEAR = 3;

    // properties whose values are points, or point lists or pairs ("ab:cd"), or a point and a
    // label (LB "ab:text")
    private static final Set<String> POINTS = Set.of("B", "W", "AB", "AW", "AE", "TR", "CR", "SQ", "MA",
            "SL", "TB", "TW", "DD", "VW", "AR", "LN", "LB");

    // the sgf on 19x19 and how far it moved: size is the original board's, dx and dy what was
    // added to every column and row (sgf rows count down from the top)
    public record Embedded(String sgf, int size, int dx, int dy) {
        public boolean moved() {
            return dx != 0 || dy != 0 || size != 19;
        }
    }

    public static Embedded toNineteen(String sgf) {
        List<Prop> props = scan(sgf);
        int size = 19;
        for (Prop p : props) {
            if (p.id.equals("SZ") && !p.values.isEmpty()) size = parseSize(p.values.get(0));
        }
        if (size == 19) return new Embedded(sgf, 19, 0, 0);
        if (size > 19) throw new IllegalArgumentException("board size " + size + " is larger than 19");

        int minX = size, maxX = -1, minY = size, maxY = -1;
        for (Prop p : props) {
            if (!POINTS.contains(p.id)) continue;
            for (String v : p.values) {
                for (String pt : points(p.id, v)) {
                    if (!onBoard(pt, size)) continue;
                    int x = pt.charAt(0) - 'a', y = pt.charAt(1) - 'a';
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        int dx = shift(minX, maxX, size, "columns");
        int dy = shift(minY, maxY, size, "rows");

        StringBuilder out = new StringBuilder(sgf.length() + 16);
        int at = 0;
        for (Prop p : props) {
            out.append(sgf, at, p.valuesStart);
            for (int i = 0; i < p.values.size(); i++) {
                out.append(p.gaps.get(i)).append('[');
                String v = p.values.get(i);
                if (p.id.equals("SZ")) out.append("19");
                else if (POINTS.contains(p.id)) out.append(move(p.id, v, size, dx, dy));
                else out.append(v);
                out.append(']');
            }
            at = p.end;
        }
        out.append(sgf.substring(at));
        return new Embedded(out.toString(), size, dx, dy);
    }

    // how far an axis moves: toward the far edge when the stones are nearer it, else not at all
    private static int shift(int min, int max, int size, String axis) {
        if (max < 0) return 0; // nothing on the board
        int near = min, far = size - 1 - max;
        boolean toFar = far < near;
        int lost = toFar ? near : far;
        if (lost < CLEAR) {
            throw new IllegalArgumentException("the problem comes within " + lost + " lines of both edges of its "
                    + size + "x" + size + " board's " + axis + ", so it cannot move to a 19x19 board");
        }
        return toFar ? 19 - size : 0;
    }

    private static int parseSize(String v) {
        String s = v.trim();
        int colon = s.indexOf(':');
        if (colon >= 0) {
            int a = Integer.parseInt(s.substring(0, colon).trim()), b = Integer.parseInt(s.substring(colon + 1).trim());
            if (a != b) throw new IllegalArgumentException("rectangular board " + s);
            return a;
        }
        return Integer.parseInt(s);
    }

    // the points a value names: a point, a pass, a point pair or compressed list "ab:cd", or a
    // point and a label
    private static List<String> points(String id, String v) {
        List<String> out = new ArrayList<>();
        if (id.equals("LB")) {
            int colon = v.indexOf(':');
            out.add(colon >= 0 ? v.substring(0, colon) : v);
        } else if (v.indexOf(':') >= 0) {
            out.add(v.substring(0, v.indexOf(':')));
            out.add(v.substring(v.indexOf(':') + 1));
        } else {
            out.add(v);
        }
        return out;
    }

    private static boolean onBoard(String pt, int size) {
        if (pt.length() != 2) return false; // a pass, or something not a point
        int x = pt.charAt(0) - 'a', y = pt.charAt(1) - 'a';
        return x >= 0 && y >= 0 && x < size && y < size;
    }

    private static String move(String id, String v, int size, int dx, int dy) {
        if (id.equals("LB")) {
            int colon = v.indexOf(':');
            return colon < 0 ? v : movePoint(v.substring(0, colon), size, dx, dy) + v.substring(colon);
        }
        int colon = v.indexOf(':');
        if (colon >= 0) return movePoint(v.substring(0, colon), size, dx, dy) + ":" + movePoint(v.substring(colon + 1), size, dx, dy);
        return movePoint(v, size, dx, dy);
    }

    private static String movePoint(String pt, int size, int dx, int dy) {
        if (!onBoard(pt, size)) return pt; // a pass stays a pass
        return "" + (char) (pt.charAt(0) + dx) + (char) (pt.charAt(1) + dy);
    }

    // a property: its identifier (lowercase letters dropped, as old sgf allowed them), its
    // values and the text before each, where its values start and where it ends
    private record Prop(String id, int valuesStart, List<String> gaps, List<String> values, int end) {
    }

    private static List<Prop> scan(String s) {
        List<Prop> out = new ArrayList<>();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (!Character.isLetter(c)) {
                i++;
                continue;
            }
            StringBuilder id = new StringBuilder();
            while (i < n && Character.isLetter(s.charAt(i))) {
                if (Character.isUpperCase(s.charAt(i))) id.append(s.charAt(i));
                i++;
            }
            int valuesStart = i;
            List<String> gaps = new ArrayList<>();
            List<String> values = new ArrayList<>();
            while (true) {
                int j = i;
                while (j < n && Character.isWhitespace(s.charAt(j))) j++;
                if (j >= n || s.charAt(j) != '[') break;
                gaps.add(s.substring(i, j));
                StringBuilder v = new StringBuilder();
                j++;
                while (j < n && s.charAt(j) != ']') {
                    if (s.charAt(j) == '\\' && j + 1 < n) {
                        v.append(s.charAt(j)).append(s.charAt(j + 1));
                        j += 2;
                    } else {
                        v.append(s.charAt(j++));
                    }
                }
                values.add(v.toString());
                i = Math.min(n, j + 1);
            }
            if (!values.isEmpty()) out.add(new Prop(id.toString(), valuesStart, gaps, values, i));
        }
        return out;
    }
}
