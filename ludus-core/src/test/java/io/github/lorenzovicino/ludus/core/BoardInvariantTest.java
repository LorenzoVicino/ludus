package io.github.lorenzovicino.ludus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The invariants that make a mutable board safe.
 *
 * <p>{@link Board} is mutated in place for speed, which means a make/unmake pair that forgets to
 * restore one field corrupts the position for every sibling branch that follows. The symptom
 * appears far from the cause: an illegal move twenty nodes later, in a different subtree. These
 * tests catch it at the node where it happens.
 *
 * <p>Random games with a fixed seed, so a failure is reproducible.
 */
class BoardInvariantTest {

    private static final long SEED = 20260808L;
    private static final int GAMES = 60;
    private static final int MAX_PLIES = 120;

    @Test
    void makeUnmakeRestoresEveryFieldExactly() {
        Random random = new Random(SEED);
        int[] moves = new int[MoveGenerator.MAX_MOVES];

        for (int game = 0; game < GAMES; game++) {
            Board board = Board.startPosition();
            for (int played = 0; played < MAX_PLIES; played++) {
                int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));
                if (count == 0) {
                    break;
                }

                String before = board.toFen();
                String signature = board.stateSignature();

                // Not just the move we go on to play: every legal move from this position has to be
                // exactly reversible, because the search will try all of them.
                for (int i = 0; i < count; i++) {
                    int move = moves[i];
                    board.makeMove(move);
                    board.unmakeMove(move);
                    assertEquals(signature, board.stateSignature(),
                            () -> "Position not restored after make/unmake of " + Move.toUci(move)
                                    + "\nFEN before: " + before);
                }

                board.makeMove(moves[random.nextInt(count)]);
            }
        }
    }

    @Test
    void incrementalZobristMatchesRecomputation() {
        Random random = new Random(SEED);
        int[] moves = new int[MoveGenerator.MAX_MOVES];

        for (int game = 0; game < GAMES; game++) {
            Board board = Board.startPosition();
            for (int played = 0; played < MAX_PLIES; played++) {
                int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));
                if (count == 0) {
                    break;
                }

                for (int i = 0; i < count; i++) {
                    int move = moves[i];
                    board.makeMove(move);
                    String fen = board.toFen();
                    assertEquals(board.recomputeZobrist(), board.zobrist(),
                            () -> "Incremental hash drifted after " + Move.toUci(move) + "\nFEN: " + fen);
                    board.unmakeMove(move);
                }

                board.makeMove(moves[random.nextInt(count)]);
            }
        }
    }

    @Test
    void fenRoundTripsAndDeterminesTheHash() {
        Random random = new Random(SEED);
        int[] moves = new int[MoveGenerator.MAX_MOVES];

        for (int game = 0; game < GAMES; game++) {
            Board board = Board.startPosition();
            for (int played = 0; played < MAX_PLIES; played++) {
                String fen = board.toFen();
                Board reparsed = Board.fromFen(fen);
                assertEquals(fen, reparsed.toFen(), "FEN did not survive a round trip");
                assertEquals(board.zobrist(), reparsed.zobrist(),
                        () -> "The hash must be a function of the position alone, not of how it was"
                                + " reached.\nFEN: " + fen);

                int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));
                if (count == 0) {
                    break;
                }
                board.makeMove(moves[random.nextInt(count)]);
            }
        }
    }

    @Test
    void startPositionRoundTripsThroughFen() {
        assertEquals(Board.START_FEN, Board.startPosition().toFen());
    }

    @Test
    void fenTolerantOfMissingClocks() {
        // Published test positions such as Kiwipete omit the halfmove clock and move number.
        Board board = Board.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq -");
        assertEquals(0, board.halfmoveClock());
        assertEquals(1, board.fullmoveNumber());
        assertEquals(Squares.NONE, board.epSquare());
        assertEquals(Castling.ALL, board.castlingRights());
    }
}
