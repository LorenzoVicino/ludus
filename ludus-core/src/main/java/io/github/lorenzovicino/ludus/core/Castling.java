package io.github.lorenzovicino.ludus.core;

/** The four castling rights, held as a 4-bit mask. */
public final class Castling {

    private Castling() {
    }

    public static final int NONE = 0;
    public static final int WHITE_KING = 1;
    public static final int WHITE_QUEEN = 2;
    public static final int BLACK_KING = 4;
    public static final int BLACK_QUEEN = 8;
    public static final int ALL = WHITE_KING | WHITE_QUEEN | BLACK_KING | BLACK_QUEEN;

    /** Number of distinct right masks — the size of the Zobrist castling table. */
    public static final int MASK_COUNT = 16;

    public static int kingSide(int color) {
        return color == Pieces.WHITE ? WHITE_KING : BLACK_KING;
    }

    public static int queenSide(int color) {
        return color == Pieces.WHITE ? WHITE_QUEEN : BLACK_QUEEN;
    }

    public static String toFen(int rights) {
        if (rights == NONE) {
            return "-";
        }
        StringBuilder out = new StringBuilder(4);
        if ((rights & WHITE_KING) != 0) {
            out.append('K');
        }
        if ((rights & WHITE_QUEEN) != 0) {
            out.append('Q');
        }
        if ((rights & BLACK_KING) != 0) {
            out.append('k');
        }
        if ((rights & BLACK_QUEEN) != 0) {
            out.append('q');
        }
        return out.toString();
    }

    public static int parse(String field) {
        if (field.equals("-")) {
            return NONE;
        }
        int rights = NONE;
        for (int i = 0; i < field.length(); i++) {
            rights |= switch (field.charAt(i)) {
                case 'K' -> WHITE_KING;
                case 'Q' -> WHITE_QUEEN;
                case 'k' -> BLACK_KING;
                case 'q' -> BLACK_QUEEN;
                default -> throw new IllegalArgumentException("Bad castling field: " + field);
            };
        }
        return rights;
    }
}
