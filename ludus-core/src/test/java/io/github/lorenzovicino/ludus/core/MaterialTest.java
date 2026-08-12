package io.github.lorenzovicino.ludus.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Two consumers read this predicate — the evaluation and the training pipeline — so being wrong here is
 * wrong twice, and in different ways. Too broad and won endgames are thrown away; too narrow and dead
 * draws keep their false score.
 */
class MaterialTest {

    @ParameterizedTest
    @DisplayName("no mate is possible with at most one minor each")
    @ValueSource(strings = {
            "8/8/4k3/8/8/2K5/8/8 w - - 0 1",          // bare kings
            "8/8/4k3/8/8/2KB4/8/8 w - - 0 1",         // king and bishop
            "8/8/4k3/8/8/2KN4/8/8 w - - 0 1",         // king and knight
            "8/5b2/4k3/8/8/2KB4/8/8 w - - 0 1",       // a bishop each
            "8/5n2/4k3/8/8/2KN4/8/8 w - - 0 1",       // a knight each
            "8/5b2/4k3/8/8/2KN4/8/8 w - - 0 1",       // knight against bishop
            "8/5n2/4k3/8/8/2KB4/8/8 b - - 0 1",       // and the other way round
    })
    void insufficientMaterialIsRecognised(String fen) {
        assertTrue(Material.isInsufficientToMate(Board.fromFen(fen)), fen);
    }

    @ParameterizedTest
    @DisplayName("anything that can force mate, or promote, is not a draw")
    @ValueSource(strings = {
            "8/8/4k3/8/8/2KR4/8/8 w - - 0 1",         // rook
            "8/8/4k3/8/8/2KQ4/8/8 w - - 0 1",         // queen
            "8/8/4k3/8/8/2KB1B2/8/8 w - - 0 1",       // two bishops force mate
            "8/8/4k3/8/8/1NKB4/8/8 w - - 0 1",        // bishop and knight force mate
            "8/8/4k3/8/8/1NKN4/8/8 w - - 0 1",        // two knights: not forced, not claimed
            "8/8/4k3/8/4P3/2K5/8/8 w - - 0 1",        // a pawn can promote
            "8/4p3/4k3/8/8/2K5/8/8 w - - 0 1",        // including the other side's
            "8/5r2/4k3/8/8/2KB4/8/8 w - - 0 1",       // a rook on either side is enough
    })
    void sufficientMaterialIsLeftAlone(String fen) {
        assertFalse(Material.isInsufficientToMate(Board.fromFen(fen)), fen);
    }

    @Test
    @DisplayName("the answer does not depend on whose turn it is")
    void sideToMoveIsIrrelevant() {
        String white = "8/8/4k3/8/8/2KB4/8/8 w - - 0 1";
        String black = "8/8/4k3/8/8/2KB4/8/8 b - - 0 1";
        assertTrue(Material.isInsufficientToMate(Board.fromFen(white)));
        assertTrue(Material.isInsufficientToMate(Board.fromFen(black)));
    }

    @Test
    @DisplayName("the start position is emphatically not a draw")
    void theStartPositionIsNotADraw() {
        assertFalse(Material.isInsufficientToMate(Board.startPosition()));
    }
}
