package io.github.lorenzovicino.ludus.core;

import java.util.Random;

/**
 * Precomputed attack sets. Stepping pieces get a plain table per square; sliding pieces use
 * magic bitboards.
 *
 * <h2>Why the magics are searched at class initialisation</h2>
 *
 * <p>DESIGN.md §4.1 called for hardcoding published magic numbers. This class searches for them
 * instead, with a fixed seed, and the reason is worth stating: a wrong magic does not fail
 * loudly. It silently returns an attack set for the wrong occupancy, which corrupts move
 * generation in a way that surfaces as an impossible move a thousand nodes later. Transcribing
 * 128 hand-copied 64-bit constants is exactly the kind of task where a single wrong digit
 * survives review.
 *
 * <p>A search cannot make that mistake, because it <em>verifies</em> each candidate against the
 * reference implementation for every occupancy before accepting it. The tables are correct by
 * construction rather than correct by transcription. The cost is tens of milliseconds at
 * startup, paid once; if that ever matters, the found magics can be dumped and pinned.
 *
 * <p>The fixed seeds make the search deterministic, so every run of every build produces
 * byte-identical tables.
 */
public final class Attacks {

    private Attacks() {
    }

    private static final int[][] ROOK_DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] BISHOP_DIRECTIONS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static final int[] KNIGHT_FILE_DELTAS = {1, 2, 2, 1, -1, -2, -2, -1};
    private static final int[] KNIGHT_RANK_DELTAS = {2, 1, -1, -2, -2, -1, 1, 2};
    private static final int[] KING_FILE_DELTAS = {0, 1, 1, 1, 0, -1, -1, -1};
    private static final int[] KING_RANK_DELTAS = {1, 1, 0, -1, -1, -1, 0, 1};

    private static final long ROOK_SEED = 0x5EED_0001L;
    private static final long BISHOP_SEED = 0x5EED_0002L;

    private static final long[] KNIGHT_ATTACKS = new long[Squares.COUNT];
    private static final long[] KING_ATTACKS = new long[Squares.COUNT];
    private static final long[][] PAWN_ATTACKS = new long[Pieces.COLOR_COUNT][Squares.COUNT];

    private static final long[] ROOK_MASK = new long[Squares.COUNT];
    private static final long[] ROOK_MAGIC = new long[Squares.COUNT];
    private static final int[] ROOK_SHIFT = new int[Squares.COUNT];
    private static final long[][] ROOK_TABLE = new long[Squares.COUNT][];

    private static final long[] BISHOP_MASK = new long[Squares.COUNT];
    private static final long[] BISHOP_MAGIC = new long[Squares.COUNT];
    private static final int[] BISHOP_SHIFT = new int[Squares.COUNT];
    private static final long[][] BISHOP_TABLE = new long[Squares.COUNT][];

    static {
        initStepAttacks();
        initMagics(ROOK_DIRECTIONS, ROOK_MASK, ROOK_MAGIC, ROOK_SHIFT, ROOK_TABLE, ROOK_SEED);
        initMagics(BISHOP_DIRECTIONS, BISHOP_MASK, BISHOP_MAGIC, BISHOP_SHIFT, BISHOP_TABLE, BISHOP_SEED);
    }

    public static long knight(int square) {
        return KNIGHT_ATTACKS[square];
    }

    public static long king(int square) {
        return KING_ATTACKS[square];
    }

    /** Squares attacked by a {@code color} pawn standing on {@code square}. */
    public static long pawn(int color, int square) {
        return PAWN_ATTACKS[color][square];
    }

    public static long rook(int square, long occupied) {
        long blockers = occupied & ROOK_MASK[square];
        return ROOK_TABLE[square][(int) ((blockers * ROOK_MAGIC[square]) >>> ROOK_SHIFT[square])];
    }

    public static long bishop(int square, long occupied) {
        long blockers = occupied & BISHOP_MASK[square];
        return BISHOP_TABLE[square][(int) ((blockers * BISHOP_MAGIC[square]) >>> BISHOP_SHIFT[square])];
    }

    public static long queen(int square, long occupied) {
        return rook(square, occupied) | bishop(square, occupied);
    }

    public static long slider(int type, int square, long occupied) {
        return switch (type) {
            case Pieces.BISHOP -> bishop(square, occupied);
            case Pieces.ROOK -> rook(square, occupied);
            case Pieces.QUEEN -> queen(square, occupied);
            default -> throw new IllegalArgumentException("Not a sliding piece: " + type);
        };
    }

    private static void initStepAttacks() {
        for (int square = 0; square < Squares.COUNT; square++) {
            int file = Squares.file(square);
            int rank = Squares.rank(square);
            for (int i = 0; i < 8; i++) {
                int knightFile = file + KNIGHT_FILE_DELTAS[i];
                int knightRank = rank + KNIGHT_RANK_DELTAS[i];
                if (Squares.isValid(knightFile, knightRank)) {
                    KNIGHT_ATTACKS[square] |= Bitboards.bit(Squares.of(knightFile, knightRank));
                }
                int kingFile = file + KING_FILE_DELTAS[i];
                int kingRank = rank + KING_RANK_DELTAS[i];
                if (Squares.isValid(kingFile, kingRank)) {
                    KING_ATTACKS[square] |= Bitboards.bit(Squares.of(kingFile, kingRank));
                }
            }
            long from = Bitboards.bit(square);
            PAWN_ATTACKS[Pieces.WHITE][square] =
                    ((from & ~Bitboards.FILE_A) << 7) | ((from & ~Bitboards.FILE_H) << 9);
            PAWN_ATTACKS[Pieces.BLACK][square] =
                    ((from & ~Bitboards.FILE_H) >>> 7) | ((from & ~Bitboards.FILE_A) >>> 9);
        }
    }

    /**
     * Rook attacks by walking the rays. Exposed so tests can hold the magic tables against the
     * definition they were built from.
     */
    static long referenceRook(int square, long occupied) {
        return slidingAttacks(square, occupied, ROOK_DIRECTIONS);
    }

    /** Bishop attacks by walking the rays. See {@link #referenceRook(int, long)}. */
    static long referenceBishop(int square, long occupied) {
        return slidingAttacks(square, occupied, BISHOP_DIRECTIONS);
    }

    /**
     * Attack set reached by walking each ray until it leaves the board or hits a blocker. Slow
     * and obviously correct — the reference the magic tables are validated against, both here
     * during the search and in {@code AttacksTest}.
     */
    private static long slidingAttacks(int square, long occupied, int[][] directions) {
        long attacks = 0;
        int startFile = Squares.file(square);
        int startRank = Squares.rank(square);
        for (int[] direction : directions) {
            int file = startFile + direction[0];
            int rank = startRank + direction[1];
            while (Squares.isValid(file, rank)) {
                int reached = Squares.of(file, rank);
                attacks |= Bitboards.bit(reached);
                if (Bitboards.contains(occupied, reached)) {
                    break;
                }
                file += direction[0];
                rank += direction[1];
            }
        }
        return attacks;
    }

    /**
     * The squares whose occupancy can change this piece's attack set: every ray square except
     * the last one, since a blocker on the edge changes nothing behind it.
     */
    private static long relevantOccupancy(int square, int[][] directions) {
        long mask = 0;
        int startFile = Squares.file(square);
        int startRank = Squares.rank(square);
        for (int[] direction : directions) {
            int file = startFile + direction[0];
            int rank = startRank + direction[1];
            while (Squares.isValid(file, rank)) {
                int nextFile = file + direction[0];
                int nextRank = rank + direction[1];
                if (!Squares.isValid(nextFile, nextRank)) {
                    break;
                }
                mask |= Bitboards.bit(Squares.of(file, rank));
                file = nextFile;
                rank = nextRank;
            }
        }
        return mask;
    }

    private static void initMagics(int[][] directions, long[] masks, long[] magics, int[] shifts,
                                   long[][] tables, long seed) {
        Random random = new Random(seed);

        for (int square = 0; square < Squares.COUNT; square++) {
            long mask = relevantOccupancy(square, directions);
            int bits = Bitboards.count(mask);
            int size = 1 << bits;
            int shift = 64 - bits;
            masks[square] = mask;
            shifts[square] = shift;

            // Every occupancy of the mask, via the carry-rippler trick, with the attack set each
            // one must produce.
            long[] occupancies = new long[size];
            long[] references = new long[size];
            int count = 0;
            long subset = 0;
            do {
                occupancies[count] = subset;
                references[count] = slidingAttacks(square, subset, directions);
                count++;
                subset = (subset - mask) & mask;
            } while (subset != 0);
            if (count != size) {
                throw new AssertionError(
                        "Enumerated " + count + " occupancies, expected " + size + " on " + Squares.name(square));
            }

            long[] scratch = new long[size];
            int[] stamp = new int[size];
            int attempt = 0;
            long magic = 0;

            search:
            while (true) {
                magic = random.nextLong() & random.nextLong() & random.nextLong();

                // Cheap rejection: a magic that does not scatter the mask's high byte will not
                // scatter the occupancies either. Skipping these early makes the search
                // noticeably faster.
                if (Bitboards.count((mask * magic) >>> 56) < 6) {
                    continue;
                }

                attempt++;
                for (int i = 0; i < size; i++) {
                    int index = (int) ((occupancies[i] * magic) >>> shift);
                    if (stamp[index] != attempt) {
                        stamp[index] = attempt;
                        scratch[index] = references[i];
                    } else if (scratch[index] != references[i]) {
                        // Two occupancies with different attack sets collided. Identical attack
                        // sets sharing a slot is fine and expected.
                        continue search;
                    }
                }
                break;
            }

            // Build the table fresh from the accepted magic so no slot holds a value left over
            // from a rejected attempt.
            long[] table = new long[size];
            for (int i = 0; i < size; i++) {
                table[(int) ((occupancies[i] * magic) >>> shift)] = references[i];
            }
            magics[square] = magic;
            tables[square] = table;
        }
    }
}
