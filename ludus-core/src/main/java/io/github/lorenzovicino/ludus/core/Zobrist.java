package io.github.lorenzovicino.ludus.core;

import java.util.Random;

/**
 * Zobrist keys for incremental position hashing.
 *
 * <p>The seed is fixed and hardcoded, deliberately. Keys that differ between runs make every
 * hash-related bug unreproducible: a transposition table collision that loses a game would
 * vanish the moment you tried to look at it. {@link Random} is used rather than a hand-rolled
 * generator because its algorithm is specified exactly, so the keys are identical on every JVM.
 */
final class Zobrist {

    private Zobrist() {
    }

    private static final long SEED = 0x9E3779B97F4A7C15L;

    static final long[][] PIECE = new long[Pieces.PIECE_COUNT][Squares.COUNT];
    static final long[] CASTLING = new long[Castling.MASK_COUNT];
    static final long[] EP_FILE = new long[8];
    static final long SIDE;

    static {
        Random random = new Random(SEED);
        for (int piece = 0; piece < Pieces.PIECE_COUNT; piece++) {
            for (int square = 0; square < Squares.COUNT; square++) {
                PIECE[piece][square] = random.nextLong();
            }
        }
        for (int rights = 0; rights < Castling.MASK_COUNT; rights++) {
            CASTLING[rights] = random.nextLong();
        }
        for (int file = 0; file < 8; file++) {
            EP_FILE[file] = random.nextLong();
        }
        SIDE = random.nextLong();
    }
}
