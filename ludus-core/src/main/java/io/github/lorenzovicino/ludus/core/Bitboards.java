package io.github.lorenzovicino.ludus.core;

/** Bit manipulation over a {@code long} used as a 64-square set. */
public final class Bitboards {

    private Bitboards() {
    }

    public static final long EMPTY = 0L;
    public static final long ALL = ~0L;

    public static final long FILE_A = 0x0101010101010101L;
    public static final long FILE_B = FILE_A << 1;
    public static final long FILE_G = FILE_A << 6;
    public static final long FILE_H = FILE_A << 7;

    public static final long RANK_1 = 0xFFL;
    public static final long RANK_2 = RANK_1 << 8;
    public static final long RANK_3 = RANK_1 << 16;
    public static final long RANK_4 = RANK_1 << 24;
    public static final long RANK_5 = RANK_1 << 32;
    public static final long RANK_6 = RANK_1 << 40;
    public static final long RANK_7 = RANK_1 << 48;
    public static final long RANK_8 = RANK_1 << 56;

    public static long bit(int square) {
        return 1L << square;
    }

    public static boolean contains(long board, int square) {
        return (board & bit(square)) != 0;
    }

    public static long north(long board) {
        return board << 8;
    }

    public static long south(long board) {
        return board >>> 8;
    }

    public static long east(long board) {
        return (board & ~FILE_H) << 1;
    }

    public static long west(long board) {
        return (board & ~FILE_A) >>> 1;
    }

    /** Index of the least significant set bit. Undefined for an empty board. */
    public static int lsb(long board) {
        return Long.numberOfTrailingZeros(board);
    }

    /** {@code board} with its least significant set bit cleared. */
    public static long popLsb(long board) {
        return board & (board - 1);
    }

    public static int count(long board) {
        return Long.bitCount(board);
    }

    /** Eight lines of eight, rank 8 first — the orientation a player expects to read. */
    public static String toBoardString(long board) {
        StringBuilder out = new StringBuilder(72);
        for (int rank = 7; rank >= 0; rank--) {
            for (int file = 0; file < 8; file++) {
                out.append(contains(board, Squares.of(file, rank)) ? 'x' : '.');
            }
            out.append('\n');
        }
        return out.toString();
    }
}
