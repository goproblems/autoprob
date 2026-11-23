package autoprob.go;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;

import org.junit.jupiter.api.Test;

class NodeTest {
    // a ko on the top edge: black takes white's stone at B18 by playing C18
    private static Node koShape() {
        Node root = new Node(null);
        int[][] black = {{1, 0}, {0, 1}, {1, 2}};
        int[][] white = {{2, 0}, {3, 1}, {2, 2}, {1, 1}};
        for (int[] b : black) root.board.board[b[0]][b[1]].stone = Intersection.BLACK;
        for (int[] w : white) root.board.board[w[0]][w[1]].stone = Intersection.WHITE;
        root.defaultToMoveColor = Intersection.BLACK;
        return root;
    }

    @Test
    void aKoCannotBeTakenBackAtOnce() throws Exception {
        Node root = koShape();
        Node take = root.addBasicMove(2, 1);
        assertEquals(new Point(1, 1), take.getKo());
        assertFalse(take.legalMove(new Point(1, 1)), "white may not take the ko back at once");
        assertTrue(take.legalMove(new Point(10, 10)));

        // after a move each elsewhere it may
        Node later = take.addBasicMove(10, 10).addBasicMove(12, 12);
        assertNull(later.getKo());
        assertTrue(later.legalMove(new Point(1, 1)));
    }

    @Test
    void aPassBansNothing() throws Exception {
        Node take = koShape().addBasicMove(2, 1);
        Node pass = take.addBasicMove(19, 19);
        assertNull(pass.getKo());
    }
}
