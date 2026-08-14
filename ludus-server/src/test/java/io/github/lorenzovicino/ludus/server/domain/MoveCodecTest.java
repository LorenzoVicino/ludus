package io.github.lorenzovicino.ludus.server.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundary between what a browser sends and what the engine understands.
 *
 * <p>Everything a client can do wrong arrives here as a string, so this is where "no" has to be correct
 * and specific. The interesting cases are the moves that look like moves: the right squares in a
 * position where that piece cannot go, and the special moves whose notation is not obvious.
 */
class MoveCodecTest {

    @Test
    @DisplayName("an ordinary move round-trips")
    void ordinaryMove() {
        Board board = Board.startPosition();
        int move = MoveCodec.parse(board, "e2e4");
        assertEquals("e2e4", Move.toUci(move));
    }

    @Test
    @DisplayName("case and surrounding space do not decide legality")
    void inputIsNormalised() {
        Board board = Board.startPosition();
        assertEquals("e2e4", Move.toUci(MoveCodec.parse(board, "  E2E4 ")));
    }

    @Test
    @DisplayName("castling is the king's two squares, not a word")
    void castlingNotation() {
        Board board = Board.fromFen("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1");
        assertEquals("e1g1", Move.toUci(MoveCodec.parse(board, "e1g1")));
        assertEquals("e1c1", Move.toUci(MoveCodec.parse(board, "e1c1")));
    }

    @Test
    @DisplayName("a promotion needs its piece, and each piece is a different move")
    void promotionNeedsAPiece() {
        Board board = Board.fromFen("8/4P3/8/8/8/8/8/K6k w - - 0 1");
        assertEquals("e7e8q", Move.toUci(MoveCodec.parse(board, "e7e8q")));
        assertEquals("e7e8n", Move.toUci(MoveCodec.parse(board, "e7e8n")));
        // Without the piece it is not a legal move at all: the pawn cannot simply arrive on the eighth.
        assertThrows(IllegalMoveException.class, () -> MoveCodec.parse(board, "e7e8"));
    }

    @Test
    @DisplayName("a move that would leave the king in check does not exist")
    void pinnedPieceCannotMove() {
        // The bishop on e2 is pinned by the rook on e8; moving it would expose the king on e1.
        Board board = Board.fromFen("4r2k/8/8/8/8/8/4B3/4K3 w - - 0 1");
        IllegalMoveException thrown =
                assertThrows(IllegalMoveException.class, () -> MoveCodec.parse(board, "e2a6"));
        assertFalse(thrown.legalMoves().contains("e2a6"));
        assertFalse(thrown.legalMoves().isEmpty(), "the position is not stalemate, so say what is legal");
    }

    @Test
    @DisplayName("refusing a move says what would have been allowed")
    void refusalCarriesTheAlternatives() {
        Board board = Board.startPosition();
        IllegalMoveException thrown =
                assertThrows(IllegalMoveException.class, () -> MoveCodec.parse(board, "e2e5"));

        assertEquals("e2e5", thrown.offered());
        assertEquals(20, thrown.legalMoves().size(), "twenty moves in the start position");
        assertTrue(thrown.legalMoves().contains("e2e4"));
    }

    @Test
    @DisplayName("nonsense is refused the same way an illegal move is")
    void garbageIsJustIllegal() {
        Board board = Board.startPosition();
        assertThrows(IllegalMoveException.class, () -> MoveCodec.parse(board, "hello"));
        assertThrows(IllegalMoveException.class, () -> MoveCodec.parse(board, ""));
        assertThrows(IllegalMoveException.class, () -> MoveCodec.parse(board, null));
    }

    @Test
    @DisplayName("the legal move list matches the known count")
    void legalMoveCount() {
        assertEquals(20, MoveCodec.legalMoves(Board.startPosition()).size());
        assertTrue(MoveCodec.hasLegalMove(Board.startPosition()));
    }

    @Test
    @DisplayName("a mated side has no legal move")
    void checkmateHasNoMoves() {
        Board board = Board.fromFen("7k/5QK1/8/8/8/8/8/8 b - - 0 1");
        assertFalse(MoveCodec.hasLegalMove(board));
        assertTrue(MoveCodec.legalMoves(board).isEmpty());
    }
}
