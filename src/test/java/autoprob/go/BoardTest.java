package autoprob.go;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoardTest {
    @Test
    void suicideStaysIllegalAfterACapture() throws Exception {
        Board board = new Board();
        // black captures a white stone in the corner: B A2 and B B1 around W A1
        board.board[0][18].stone = Intersection.WHITE;
        board.board[0][17].stone = Intersection.BLACK;
        assertTrue(board.makeMove(1, 18, Intersection.BLACK));
        assertEquals(1, board.deathList.size(), "white's A1 is captured");
        assertTrue(board.isKilled());

        // a white stone surrounded by black at K10 is suicide, also on a board copied from
        // the one after the capture
        Board copy = (Board) board.clone();
        copy.board[8][8].stone = Intersection.BLACK;
        copy.board[10][8].stone = Intersection.BLACK;
        copy.board[9][7].stone = Intersection.BLACK;
        copy.board[9][9].stone = Intersection.BLACK;
        assertFalse(copy.makeMove(9, 8, Intersection.WHITE));
        assertEquals(Intersection.EMPTY, copy.board[9][8].stone);
        assertFalse(copy.isKilled(), "a move that captures nothing has killed nothing");
    }
}
