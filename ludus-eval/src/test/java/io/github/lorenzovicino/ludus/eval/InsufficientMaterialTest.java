package io.github.lorenzovicino.ludus.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Positions no legal sequence can win must score level, and positions that can be won must not.
 *
 * <p>The second half of that matters as much as the first. A check like this is one over-broad
 * condition away from throwing away won endgames, and doing so would cost far more than the bug it
 * fixes: an engine that scores K+B+B against a bare king as a draw has no reason to try to win it.
 */
class InsufficientMaterialTest {

    private final HandCraftedEvaluator evaluator = new HandCraftedEvaluator();

    private int scoreOf(String fen) {
        return evaluator.evaluate(Board.fromFen(fen));
    }

    @Test
    @DisplayName("material that cannot mate scores level")
    void deadDrawsScoreZero() {
        assertEquals(0, scoreOf("8/8/4k3/8/8/2K5/8/8 w - - 0 1"), "bare kings");
        // This one read +368 before the check existed, which is what prompted it.
        assertEquals(0, scoreOf("8/8/4k3/8/8/2KB4/8/8 w - - 0 1"), "king and bishop against a king");
        assertEquals(0, scoreOf("8/8/4k3/8/8/2KN4/8/8 w - - 0 1"), "king and knight against a king");
        assertEquals(0, scoreOf("8/5b2/4k3/8/8/2KN4/8/8 w - - 0 1"), "a minor each");
        assertEquals(0, scoreOf("8/5n2/4k3/8/8/2KN4/8/8 w - - 0 1"), "a knight each");
        assertEquals(0, scoreOf("8/5b2/4k3/8/8/2KB4/8/8 w - - 0 1"), "a bishop each");
    }

    @Test
    @DisplayName("material that can mate is left alone")
    void winnableEndgamesAreNotDiscarded() {
        assertTrue(scoreOf("8/8/4k3/8/8/2KR4/8/8 w - - 0 1") > 300,
                "king and rook against a bare king is a win and must read like one");
        assertTrue(scoreOf("8/8/4k3/8/8/2KQ4/8/8 w - - 0 1") > 700, "king and queen is a bigger win");
        assertTrue(scoreOf("8/8/4k3/8/8/2KB1B2/8/8 w - - 0 1") > 300,
                "two bishops can force mate, so this is not a draw");
        assertTrue(scoreOf("8/8/4k3/8/8/1NKB4/8/8 w - - 0 1") > 300,
                "bishop and knight can force mate, awkwardly but really");
        assertTrue(scoreOf("8/8/4k3/8/4P3/2K5/8/8 w - - 0 1") > 0,
                "a pawn can promote, so no pawn ending is dismissed");
    }

    @Test
    @DisplayName("two knights against a bare king is not claimed as a draw")
    void twoKnightsAreNotClaimed() {
        // Mate cannot be forced against correct defence, but it is reachable and does occur. "Cannot
        // be forced" and "is a draw" are different claims, and only the second justifies returning
        // zero, so this deliberately keeps its material score.
        assertTrue(scoreOf("8/8/4k3/8/8/1NKN4/8/8 w - - 0 1") > 300);
    }

    @Test
    @DisplayName("the check is symmetric")
    void theCheckDoesNotFavourAColour() {
        assertEquals(scoreOf("8/8/4k3/8/8/2KB4/8/8 w - - 0 1"),
                scoreOf("8/8/4k3/8/8/2Kb4/8/8 w - - 0 1"),
                "a bishop is as insufficient for black as it is for white");
    }
}
