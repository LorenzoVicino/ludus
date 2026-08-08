package io.github.lorenzovicino.ludus.core;

/**
 * Colors, piece types, and the combined piece codes stored in {@link Board}'s square array.
 *
 * <p>A piece code is {@code color * 6 + type}, so white occupies 0-5 and black 6-11. Keeping the
 * two orderings aligned lets {@code Board} index its type and color bitboards without a lookup.
 */
public final class Pieces {

    private Pieces() {
    }

    public static final int WHITE = 0;
    public static final int BLACK = 1;
    public static final int COLOR_COUNT = 2;

    public static final int PAWN = 0;
    public static final int KNIGHT = 1;
    public static final int BISHOP = 2;
    public static final int ROOK = 3;
    public static final int QUEEN = 4;
    public static final int KING = 5;
    public static final int TYPE_COUNT = 6;

    public static final int PIECE_COUNT = 12;

    /** Marks an empty square in {@link Board}'s square array. */
    public static final int NO_PIECE = 12;

    private static final char[] CHARS = {'P', 'N', 'B', 'R', 'Q', 'K', 'p', 'n', 'b', 'r', 'q', 'k'};

    public static int of(int color, int type) {
        return color * TYPE_COUNT + type;
    }

    public static int colorOf(int piece) {
        return piece / TYPE_COUNT;
    }

    public static int typeOf(int piece) {
        return piece % TYPE_COUNT;
    }

    public static int flip(int color) {
        return color ^ 1;
    }

    public static char toChar(int piece) {
        return CHARS[piece];
    }

    /** @return the piece code, or {@link #NO_PIECE} if {@code c} names no piece. */
    public static int fromChar(char c) {
        for (int piece = 0; piece < PIECE_COUNT; piece++) {
            if (CHARS[piece] == c) {
                return piece;
            }
        }
        return NO_PIECE;
    }
}
