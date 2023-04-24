package autoprob.go.action;

import autoprob.go.Intersection;
import autoprob.go.Node;

// SGF comment
// if contains 'RIGHT', this path is a solution

public class CommentAction extends Action {
    public String comment;

    public CommentAction(String src) {
        comment = "" + src;
    }

    // constructor from SGF source
    public CommentAction(String src, Node node) {
        src = src.trim();

        // parse for special command strings
        int pos;

        pos = src.indexOf("RIGHT");
        if (pos >= 0) {
            src = src.substring(0, pos) + src.substring(pos + 5, src.length());
            node.result = Intersection.RIGHT;
        }

        pos = src.indexOf("WRONG");
        if (pos >= 0) {
            src = src.substring(0, pos) + src.substring(pos + 5, src.length());
            node.result = Intersection.WRONG;
        }

        pos = src.indexOf("CHOICE");
        if (pos >= 0) {
            src = src.substring(0, pos) + src.substring(pos + 6, src.length());
            node.isChoice = true;
        }

        pos = src.indexOf("FORCE");
        if (pos >= 0) {
            src = src.substring(0, pos) + src.substring(pos + 5, src.length());
            node.forceMove = true;
        }

        pos = src.indexOf("NOTTHIS");
        if (pos >= 0) {
            src = src.substring(0, pos) + src.substring(pos + 7, src.length());
            node.notThis = true;
        }

        comment = "" + src;
        node.comment = comment;
    }

    public String outputSGF() {
        return "C[" + comment + "]";
    }
}
