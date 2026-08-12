package io.github.lorenzovicino.ludus.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import io.github.lorenzovicino.ludus.core.Pieces;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An illegal position in the training set would never fail anything. It would be labelled by a search
 * that accepted it, trained on, and would simply make the network slightly wrong for reasons nothing
 * reports. So the invariants are asserted here rather than trusted.
 */
class EndgameSeedsTest {

    @Test
    @DisplayName("every generated position is one the engine can legally play from")
    void everyPositionIsLegalAndPlayable() {
        List<String> positions = EndgameSeeds.generate(400, 20260810L);
        assertEquals(400, positions.size(), "the generator gave up early");

        int[] scratch = new int[MoveGenerator.MAX_MOVES];
        for (String fen : positions) {
            Board board = Board.fromFen(fen);

            // The side that just moved cannot still be in check: that position could not have arisen.
            assertFalse(board.isKingAttacked(Pieces.flip(board.sideToMove())),
                    "side not to move is in check: " + fen);

            int legal = MoveGenerator.filterLegal(board, scratch, MoveGenerator.generate(board, scratch));
            assertTrue(legal > 0, "no legal moves, the game is already over: " + fen);

            assertEquals(fen, board.toFen(), "the position does not survive a FEN round trip");
        }
    }

    @Test
    @DisplayName("no pawn stands on a rank it could not be on")
    void noPawnsOnTheFirstOrEighthRank() {
        for (String fen : EndgameSeeds.generate(400, 99L)) {
            String ranks = fen.split(" ")[0];
            String eighth = ranks.substring(0, ranks.indexOf('/'));
            String first = ranks.substring(ranks.lastIndexOf('/') + 1);
            assertFalse(eighth.indexOf('p') >= 0 || eighth.indexOf('P') >= 0,
                    "pawn on the eighth rank: " + fen);
            assertFalse(first.indexOf('p') >= 0 || first.indexOf('P') >= 0,
                    "pawn on the first rank: " + fen);
        }
    }

    @Test
    @DisplayName("these are endgames, which is the entire point of the class")
    void positionsAreActuallyEndgames() {
        for (String fen : EndgameSeeds.generate(300, 7L)) {
            long pieces = fen.split(" ")[0].chars().filter(Character::isLetter).count();
            assertTrue(pieces <= 10, "not an endgame, " + pieces + " pieces: " + fen);
            assertTrue(pieces >= 2, "a position needs two kings: " + fen);
        }
    }

    @Test
    @DisplayName("the same seed gives the same positions, so a job replays identically anywhere")
    void generationIsReproducible() {
        assertEquals(EndgameSeeds.generate(50, 4242L), EndgameSeeds.generate(50, 4242L));
        assertFalse(EndgameSeeds.generate(50, 4242L).equals(EndgameSeeds.generate(50, 4243L)),
                "different seeds produced identical positions");
    }
}
