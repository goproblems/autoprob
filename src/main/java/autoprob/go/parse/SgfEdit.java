package autoprob.go.parse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// an sgf as written, for adding lines to it and nothing else: every node keeps its properties
// exactly as the text has them, so what comes out differs from what came in only by what was
// added. the site compares a suggested problem with its own node by node, and anything else
// that changed (a root property in another order, a board moved to 19x19) would show there as
// an edit to the problem. moves are "B ka": a color and an sgf point
public class SgfEdit {
    public static class SgfNode {
        String props; // the node's text after its ';'
        final List<SgfNode> children = new ArrayList<>();

        SgfNode(String props) {
            this.props = props;
        }

        // the move the node plays, or null
        public String move() {
            for (String[] p : properties(props)) {
                if ((p[0].equals("B") || p[0].equals("W")) && p.length > 1) return p[0] + " " + p[1];
            }
            return null;
        }

        // whether the node ends a correct line, as goproblems marks it
        public boolean right() {
            for (String[] p : properties(props)) {
                if (p[0].equals("C") && p.length > 1 && p[1].contains("RIGHT")) return true;
            }
            return false;
        }

        // the child that plays the move, or null
        public SgfNode child(String move) {
            for (SgfNode c : children) {
                if (Objects.equals(move, c.move())) return c;
            }
            return null;
        }

        public List<SgfNode> children() {
            return Collections.unmodifiableList(children);
        }

        // whether a line through the node ends right: the node itself or one below it
        public boolean leadsRight() {
            if (right()) return true;
            for (SgfNode c : children) {
                if (c.leadsRight()) return true;
            }
            return false;
        }
    }

    private final SgfNode root;

    private SgfEdit(SgfNode root) {
        this.root = root;
    }

    public static SgfEdit parse(String sgf) {
        Reader r = new Reader(sgf);
        r.skipSpace();
        r.expect('(');
        SgfNode root = r.tree();
        return new SgfEdit(root);
    }

    // the sgf, compact: a single child follows its parent, two or more are variations
    public String write() {
        StringBuilder sb = new StringBuilder("(");
        write(root, sb);
        return sb.append(")").toString();
    }

    private static void write(SgfNode node, StringBuilder sb) {
        sb.append(';').append(node.props);
        if (node.children.size() == 1) {
            write(node.children.get(0), sb);
            return;
        }
        for (SgfNode c : node.children) {
            sb.append('(');
            write(c, sb);
            sb.append(')');
        }
    }

    public SgfNode root() {
        return root;
    }

    // adds a node after the moves that plays the move, marked right or not, as an edit by hand
    // does; the problem has to have the position, and not the move there yet
    public void add(List<String> at, String move, boolean right) {
        SgfNode node = at(at);
        if (node == null) throw new IllegalArgumentException("the problem does not have the position " + String.join(" ", at));
        if (node.child(move) != null) throw new IllegalArgumentException(move + " is there already after " + String.join(" ", at));
        String[] cp = move.split(" ");
        if (cp.length != 2 || !cp[0].matches("[BW]") || !cp[1].matches("[a-s]{2}")) throw new IllegalArgumentException("not a move: " + move);
        node.children.add(new SgfNode(cp[0] + "[" + cp[1] + "]" + (right ? "C[RIGHT]" : "")));
    }

    // the node the moves reach from the root, or null when the problem does not have them
    public SgfNode at(List<String> moves) {
        SgfNode node = root;
        for (String m : moves) {
            node = node.child(m);
            if (node == null) return null;
        }
        return node;
    }

    // whether the problem already has the line after the moves as correct: it reaches a node
    // that ends right, or that a longer correct line goes on from
    public boolean has(List<String> at, List<String> line) {
        SgfNode node = at(at);
        if (node == null) return false;
        for (String m : line) {
            node = node.child(m);
            if (node == null) return false;
        }
        return node.leadsRight();
    }

    // adds the line after the moves, marked right at its end, reusing what the problem already
    // has of it; a line the problem has already adds nothing. a line that ends on a node of the
    // problem's that leads only to wrong endings is refused: marking it right would leave its
    // wrong answers below it, so a line through those answers has to come first. how many
    // nodes it added
    public int addLine(List<String> at, List<String> line) {
        SgfNode node = at(at);
        if (node == null) throw new IllegalArgumentException("the problem does not have the position " + String.join(" ", at));
        if (has(at, line)) return 0;
        int added = 0;
        for (int i = 0; i < line.size(); i++) {
            String m = line.get(i);
            boolean last = i == line.size() - 1;
            SgfNode next = node.child(m);
            if (next == null) {
                String[] cp = m.split(" ");
                next = new SgfNode(cp[0] + "[" + cp[1] + "]" + (last ? "C[RIGHT]" : ""));
                node.children.add(next);
                added++;
            } else if (last) {
                throw new IllegalArgumentException("the line ends on " + m + ", which the problem has on a wrong line");
            }
            node = next;
        }
        return added;
    }

    // a node's properties, each as its identifier followed by its values
    static List<String[]> properties(String props) {
        List<String[]> out = new ArrayList<>();
        int i = 0;
        while (i < props.length()) {
            while (i < props.length() && !Character.isUpperCase(props.charAt(i))) i++;
            int start = i;
            while (i < props.length() && Character.isUpperCase(props.charAt(i))) i++;
            if (start == i) break;
            List<String> p = new ArrayList<>();
            p.add(props.substring(start, i));
            while (true) {
                while (i < props.length() && Character.isWhitespace(props.charAt(i))) i++;
                if (i >= props.length() || props.charAt(i) != '[') break;
                StringBuilder v = new StringBuilder();
                i++;
                while (i < props.length() && props.charAt(i) != ']') {
                    if (props.charAt(i) == '\\' && i + 1 < props.length()) i++;
                    v.append(props.charAt(i++));
                }
                i++;
                p.add(v.toString());
            }
            out.add(p.toArray(new String[0]));
        }
        return out;
    }

    private static class Reader {
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s;
        }

        // a game tree, after its '(': its sequence of nodes, each the child of the one before,
        // and its variations, children of the sequence's last node
        SgfNode tree() {
            SgfNode first = null, last = null;
            skipSpace();
            while (peek() == ';') {
                i++;
                SgfNode n = new SgfNode(props());
                if (first == null) first = n;
                else last.children.add(n);
                last = n;
                skipSpace();
            }
            if (first == null) throw new IllegalArgumentException("an sgf game tree without a node at " + i);
            while (peek() == '(') {
                i++;
                last.children.add(tree());
                skipSpace();
            }
            expect(')');
            return first;
        }

        // a node's properties as written, up to the next node or tree outside a value
        private String props() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == '[') {
                    i++;
                    while (i < s.length() && s.charAt(i) != ']') {
                        if (s.charAt(i) == '\\') i++;
                        i++;
                    }
                } else if (c == ';' || c == '(' || c == ')') {
                    break;
                }
                i++;
            }
            return s.substring(start, i).strip();
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : 0;
        }

        void expect(char c) {
            if (peek() != c) throw new IllegalArgumentException("expected '" + c + "' at " + i + " in the sgf");
            i++;
        }

        void skipSpace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
