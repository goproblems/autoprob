package autoprob.collection;

import autoprob.go.Node;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/** What the detectors that read a problem's own solution tree ask of it. */
final class SolutionTree {
    private SolutionTree() {
    }

    /** The node the solver first has moves to choose from. */
    static Node firstChoice(Node root) {
        Node node = root;
        while (!node.babies.isEmpty()) {
            for (Node child : node.babies) {
                if (child.hasMove) return node;
            }
            node = node.babies.firstElement();
        }
        return node;
    }

    /** The first moves the tree calls right: those with a node marked right somewhere below. */
    static List<Node> rightMoves(Node start) {
        List<Node> right = new ArrayList<>();
        for (Node child : start.babies) {
            if (!child.hasMove || child.notThis) continue;
            Point point = child.findMove();
            if (point == null || !start.board.inBoard(point.x, point.y)) continue;
            if (child.searchForTheTruth()) right.add(child);
        }
        return right;
    }

    /**
     * The first line under the move that reaches a node marked right: the move's own node, then at
     * each step the first answer that still leads to one.
     */
    static List<Node> rightLine(Node moveNode) {
        List<Node> line = new ArrayList<>();
        Node node = moveNode;
        while (node != null) {
            line.add(node);
            Node following = null;
            for (Node child : node.babies) {
                if (!child.notThis && child.searchForTheTruth()) {
                    following = child;
                    break;
                }
            }
            node = following;
        }
        return line;
    }
}
