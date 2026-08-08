package io.github.lorenzovicino.ludus.core;

/**
 * Square numbering: {@code A1 = 0}, {@code H1 = 7}, {@code A8 = 56}, {@code H8 = 63}.
 *
 * <p>This little-endian rank-file mapping is what makes the bitboard shifts in {@link Bitboards}
 * fall out naturally — one rank north is {@code << 8}, one file east is {@code << 1}.
 */
public final class Squares {

    private Squares() {
    }

    /** No square. Distinct from every real square so it can mark an absent en passant target. */
    public static final int NONE = -1;

    public static final int A1 = 0, B1 = 1, C1 = 2, D1 = 3, E1 = 4, F1 = 5, G1 = 6, H1 = 7;
    public static final int A8 = 56, B8 = 57, C8 = 58, D8 = 59, E8 = 60, F8 = 61, G8 = 62, H8 = 63;

    public static final int COUNT = 64;

    public static int of(int file, int rank) {
        return (rank << 3) | file;
    }

    public static int file(int square) {
        return square & 7;
    }

    public static int rank(int square) {
        return square >>> 3;
    }

    public static boolean isValid(int file, int rank) {
        return (file | rank | (7 - file) | (7 - rank)) >= 0;
    }

    public static String name(int square) {
        if (square == NONE) {
            return "-";
        }
        return "" + (char) ('a' + file(square)) + (char) ('1' + rank(square));
    }

    /** Parses algebraic notation such as {@code "e4"}, or {@code "-"} for {@link #NONE}. */
    public static int parse(String text) {
        if (text.equals("-")) {
            return NONE;
        }
        if (text.length() != 2) {
            throw new IllegalArgumentException("Not a square: " + text);
        }
        int file = text.charAt(0) - 'a';
        int rank = text.charAt(1) - '1';
        if (!isValid(file, rank)) {
            throw new IllegalArgumentException("Not a square: " + text);
        }
        return of(file, rank);
    }
}
