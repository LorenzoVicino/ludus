package io.github.lorenzovicino.ludus.server.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lorenzovicino.ludus.core.Board;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Every way a game can be over, including the ones that are easy to report as the wrong colour. */
class RulesTest {

    private GameStatus statusOf(String fen) {
        return Rules.statusOf(Board.fromFen(fen));
    }

    @Test
    @DisplayName("a game in progress is in progress")
    void inProgress() {
        assertEquals(GameStatus.IN_PROGRESS, statusOf(Board.START_FEN));
    }

    @Test
    @DisplayName("the side that cannot move is the side that lost")
    void checkmateNamesTheWinner() {
        // Black to move and mated, so white won. Getting this backwards is the obvious mistake and it
        // would be invisible in every position where the game is still going.
        assertEquals(GameStatus.WHITE_WON, statusOf("7k/5QK1/8/8/8/8/8/8 b - - 0 1"));
        assertEquals(GameStatus.BLACK_WON, statusOf("8/8/8/8/8/8/5qk1/7K w - - 0 1"));
    }

    @Test
    @DisplayName("no moves and not in check is a draw, not a loss")
    void stalemate() {
        assertEquals(GameStatus.DRAW_STALEMATE, statusOf("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"));
    }

    @Test
    @DisplayName("material that cannot mate is a draw even with moves available")
    void insufficientMaterial() {
        assertEquals(GameStatus.DRAW_INSUFFICIENT_MATERIAL, statusOf("8/8/4k3/8/8/2KB4/8/8 w - - 0 1"));
        assertEquals(GameStatus.DRAW_INSUFFICIENT_MATERIAL, statusOf("8/8/4k3/8/8/2K5/8/8 w - - 0 1"));
    }

    @Test
    @DisplayName("a rook still on the board is not insufficient material")
    void aRookIsEnough() {
        assertEquals(GameStatus.IN_PROGRESS, statusOf("8/8/4k3/8/8/2KR4/8/8 w - - 0 1"));
    }

    @Test
    @DisplayName("the fifty-move counter ends the game")
    void fiftyMoveRule() {
        // The halfmove clock is the fifth field: a hundred half-moves without a capture or a pawn move.
        assertEquals(GameStatus.DRAW_FIFTY_MOVE, statusOf("8/8/4k3/8/8/2KR4/8/8 w - - 100 60"));
    }
}
