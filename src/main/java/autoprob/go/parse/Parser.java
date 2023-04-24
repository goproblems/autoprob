package autoprob.go.parse;

import autoprob.go.Node;

// this class parses SGF text and converts it into a Node tree

public class Parser {
    // internal class used in the parsing process
    class StringPair {
        String x, xs;

        public StringPair() {
        }

        public StringPair(String inx, String inxs) {
            x = inx;
            xs = inxs;
        }
    }

    // takes a string and returns a string pair: the first token encountered and
    // the rest of the string
    // we have to watch for crap like AW[dg][hg] too, (is this standard Smart
    // Game Format?)
    private StringPair grabToken(String s) throws Exception {
        int i;
        s = s.trim();
        String tok = "";
        int start = 0, end = 0;
        if (s.charAt(0) == '(') {
            // gotta find matching parenthesis
            int dpth = 1;
            start = 1;
            for (i = 1; i < s.length(); i++) {
                if (s.charAt(i) == '[') // find matching bracket or throw
                    while (s.charAt(i) != ']')
                        i++;
                if (s.charAt(i) == '(')
                    dpth++;
                if (s.charAt(i) == ')')
                    dpth--;
                if (dpth == 0)
                    break;
            }
            if (dpth == 0)
                end = i;
            else {
                // ended on bad depth
                System.out.println("illegal token (bad depth): " + s);
                throw new Exception("illegal token (bad depth): " + s);
            }
        }
        else if (s.charAt(0) == ';') {
            int dpth = 0;
            start = 1;
            for (i = 1; i < s.length(); i++) {
                if (s.charAt(i) == '[') // find matching bracket or throw
                    while (s.charAt(i) != ']')
                        i++;
                if ((s.charAt(i) == '(' || s.charAt(i) == ';'))
                    break;
            }
            if (dpth == 0)
                end = i;
            else {
                System.out.println("illegal ; token " + s);
                throw new Exception("illegal ; token " + s);
            }
        }
        else {
            System.out.println("illegal start of token string: " + s.charAt(0) + " in " + s);
            throw new Exception("illegal start of token string: " + s.charAt(0) + " in " + s);
        }

        tok = s.substring(start, end);
        tok = tok.trim();
        s = s.substring(end, s.length());
        s = s.trim();
        if (s.length() > 0)
            if (s.charAt(0) == ')')
                s = s.substring(1, s.length());
        return new StringPair(tok, s);
    }

    // convert a string into a Node (including sub-nodes)
    public Node parseNode(String x, Node mommy) throws Exception {
        StringPair tok = grabToken(x);
        Node node = new Node(tok.x, mommy);
        if (tok.xs.length() > 0) {
            if (tok.xs.charAt(0) == ';')
                node.addChild(parseNode(tok.xs, node));
            else {
                while (tok.xs.length() > 0) {
                    tok = grabToken(tok.xs);
                    node.addChild(parseNode(tok.x, node));
                }
            }
        }
        return node;
    }

    public Node parse(String sgf) throws Exception {
        sgf = sgf.trim();
        StringPair tok = grabToken(sgf);
        return parseNode(tok.x, null);
    }
}
