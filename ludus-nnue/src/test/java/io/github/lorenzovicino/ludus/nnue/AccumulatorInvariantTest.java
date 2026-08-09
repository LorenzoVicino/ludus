package io.github.lorenzovicino.ludus.nnue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import io.github.lorenzovicino.ludus.core.Pieces;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The invariant the whole incremental scheme rests on.
 *
 * <p>A drift between the carried-forward accumulator and a full recomputation breaks nothing loudly.
 * The engine does not crash, no test elsewhere turns red — it just evaluates positions slightly
 * wrongly, for reasons no log will ever show, and plays worse. Without this test you would look for
 * that for weeks.
 *
 * <p>Random weights are as good as trained ones here: the property being checked is arithmetic, not
 * meaning. That is what makes this testable before a single training run has happened.
 */
class AccumulatorInvariantTest {

    private static final long SEED = 20260809L;
    private static final int GAMES = 30;
    private static final int MAX_PLIES = 80;

    private final NnueNetwork network = NnueNetwork.random(SEED);

    @Test
    void theIncrementalUpdateMatchesAFullRecomputationBitForBit() {
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Accumulator accumulator = evaluator.accumulator();
        Random random = new Random(SEED);
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int checked = 0;

        for (int game = 0; game < GAMES; game++) {
            Board board = Board.startPosition();
            evaluator.reset(board);

            for (int ply = 0; ply < MAX_PLIES; ply++) {
                int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));
                if (count == 0) {
                    break;
                }

                // Every legal move, not only the one played: the search tries them all, and each one
                // pushes and pops the accumulator.
                for (int i = 0; i < count; i++) {
                    int move = moves[i];

                    evaluator.beforeMakeMove(board, move);
                    board.makeMove(move);

                    short[][] fresh = accumulator.computeFresh(board);
                    assertArrayEquals(fresh[Pieces.WHITE], accumulator.perspective(Pieces.WHITE),
                            () -> "White accumulator drifted after " + Move.toUci(move)
                                    + "\nposition: " + board.toFen());
                    assertArrayEquals(fresh[Pieces.BLACK], accumulator.perspective(Pieces.BLACK),
                            () -> "Black accumulator drifted after " + Move.toUci(move)
                                    + "\nposition: " + board.toFen());
                    checked++;

                    board.unmakeMove(move);
                    evaluator.afterUnmakeMove(board, move);
                }

                board.makeMove(moves[random.nextInt(count)]);
                // The played move bypasses the hooks, exactly as the UCI layer replays a game, so the
                // accumulator has to be re-seeded from the new position.
                evaluator.reset(board);
            }
        }

        int positions = checked;
        assertTrue(positions > 10_000,
                () -> "Too few positions to be convincing: " + positions);
        System.out.printf("accumulator invariant held over %,d positions%n", positions);
    }

    @Test
    void pushAndPopLeaveTheStackWhereTheyFoundIt() {
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Accumulator accumulator = evaluator.accumulator();
        Board board = Board.startPosition();
        evaluator.reset(board);

        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));

        short[] before = accumulator.perspective(Pieces.WHITE).clone();
        for (int i = 0; i < count; i++) {
            evaluator.beforeMakeMove(board, moves[i]);
            board.makeMove(moves[i]);
            board.unmakeMove(moves[i]);
            evaluator.afterUnmakeMove(board, moves[i]);
        }

        assertEquals(0, accumulator.ply(), "Unbalanced pushes would exhaust the stack mid-search");
        assertArrayEquals(before, accumulator.perspective(Pieces.WHITE));
    }

    @Test
    void deepSequencesStayCorrect() {
        // Quiescence can run far past the nominal depth, so the stack is exercised deep rather than
        // wide as well.
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Accumulator accumulator = evaluator.accumulator();
        Board board = Board.startPosition();
        evaluator.reset(board);

        Random random = new Random(SEED);
        int[] played = new int[120];
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int depth = 0;

        while (depth < played.length) {
            int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));
            if (count == 0) {
                break;
            }
            int move = moves[random.nextInt(count)];
            played[depth++] = move;

            evaluator.beforeMakeMove(board, move);
            board.makeMove(move);
        }

        short[][] fresh = accumulator.computeFresh(board);
        assertArrayEquals(fresh[Pieces.WHITE], accumulator.perspective(Pieces.WHITE),
                "Drift accumulates: a hundred plies deep is where a small error becomes visible");
        assertArrayEquals(fresh[Pieces.BLACK], accumulator.perspective(Pieces.BLACK));

        while (depth > 0) {
            int move = played[--depth];
            board.unmakeMove(move);
            evaluator.afterUnmakeMove(board, move);
        }
        assertEquals(0, accumulator.ply());
    }
}
