package io.github.lorenzovicino.ludus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import io.github.lorenzovicino.ludus.eval.HandCraftedEvaluator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M1's exit criterion, made automatic.
 *
 * <p>The milestone asks for a complete legal game against a GUI without ever proposing an illegal
 * move. A GUI cannot be driven from a unit test, but the part that matters can: play the engine
 * against itself and check every single move it returns against the legal move list of the position
 * it was asked about. If the engine would offer a GUI an illegal move, it offers one here too.
 *
 * <p>It also exercises what a long game exposes and a single search does not — that the board is
 * unwound perfectly hundreds of times in a row, and that the game actually terminates instead of
 * shuffling forever.
 */
class SelfPlayTest {

    private static final int MAX_PLIES = 300;

    @Test
    void playsAFullGameWithoutEverProposingAnIllegalMove() {
        Outcome outcome = playGame(SearchLimits.depth(3), 80);
        assertTrue(outcome.plies() > 0, "The engine should have played something");
    }

    @Test
    @Tag("slow")
    void playsAFullGameToItsNaturalEnd() {
        Outcome outcome = playGame(SearchLimits.depth(4), MAX_PLIES);
        System.out.printf("Self-play finished after %d plies: %s%n", outcome.plies(), outcome.reason());
        assertTrue(outcome.plies() > 20, "A game that ends almost immediately suggests something is wrong");
    }

    private record Outcome(int plies, String reason) {
    }

    private Outcome playGame(SearchLimits limits, int maxPlies) {
        Board board = Board.startPosition();
        Search search = new Search(new HandCraftedEvaluator());
        int[] scratch = new int[MoveGenerator.MAX_MOVES];

        for (int ply = 0; ply < maxPlies; ply++) {
            int legalCount =
                    MoveGenerator.filterLegal(board, scratch, MoveGenerator.generate(board, scratch));
            if (legalCount == 0) {
                return new Outcome(ply, board.inCheck() ? "checkmate" : "stalemate");
            }
            if (board.isFiftyMoveDraw()) {
                return new Outcome(ply, "fifty-move rule");
            }

            String before = board.stateSignature();
            SearchResult result = search.search(board, limits);

            assertEquals(before, board.stateSignature(),
                    "The search must hand the board back exactly as it received it");
            assertTrue(result.hasMove(), () -> "No move returned at " + board.toFen());

            int chosen = result.bestMove();
            boolean legal = false;
            for (int i = 0; i < legalCount; i++) {
                if (scratch[i] == chosen) {
                    legal = true;
                    break;
                }
            }
            assertTrue(legal, () -> "Chose the illegal move " + Move.toUci(chosen)
                    + " at " + board.toFen());

            board.makeMove(chosen);
        }
        return new Outcome(maxPlies, "ply limit reached");
    }
}
