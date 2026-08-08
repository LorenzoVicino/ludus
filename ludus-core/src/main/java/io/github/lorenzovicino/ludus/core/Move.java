package io.github.lorenzovicino.ludus.core;

/**
 * A move packed into an {@code int}: {@code from} in bits 0-5, {@code to} in bits 6-11, and a
 * 4-bit flag in bits 12-15.
 *
 * <p>Moves are not objects. The search visits millions of nodes per second and generates a move
 * list at every one of them; allocating there would put the JVM's allocator, not the engine, on
 * the critical path. See DESIGN.md §3.3.
 *
 * <p>The flag encoding is the conventional one, chosen because two of its bits carry meaning
 * directly: bit 2 marks a capture and bit 3 marks a promotion, so both questions are answered
 * without a branch table, and the promoted piece type is {@code KNIGHT + (flags & 3)}.
 */
public final class Move {

    private Move() {
    }

    /**
     * Not a move. Usable as a sentinel because a real move never has {@code from == to == 0}.
     */
    public static final int NONE = 0;

    public static final int QUIET = 0;
    public static final int DOUBLE_PUSH = 1;
    public static final int CASTLE_KING = 2;
    public static final int CASTLE_QUEEN = 3;
    public static final int CAPTURE = 4;
    public static final int EP_CAPTURE = 5;
    public static final int PROMO_KNIGHT = 8;
    public static final int PROMO_BISHOP = 9;
    public static final int PROMO_ROOK = 10;
    public static final int PROMO_QUEEN = 11;
    public static final int PROMO_CAPTURE_KNIGHT = 12;
    public static final int PROMO_CAPTURE_BISHOP = 13;
    public static final int PROMO_CAPTURE_ROOK = 14;
    public static final int PROMO_CAPTURE_QUEEN = 15;

    private static final int CAPTURE_BIT = 4;
    private static final int PROMOTION_BIT = 8;

    public static int of(int from, int to, int flags) {
        return from | (to << 6) | (flags << 12);
    }

    public static int from(int move) {
        return move & 63;
    }

    public static int to(int move) {
        return (move >>> 6) & 63;
    }

    public static int flags(int move) {
        return (move >>> 12) & 15;
    }

    /** True for ordinary captures, en passant, and capturing promotions alike. */
    public static boolean isCapture(int move) {
        return (flags(move) & CAPTURE_BIT) != 0;
    }

    public static boolean isPromotion(int move) {
        return (flags(move) & PROMOTION_BIT) != 0;
    }

    public static boolean isEnPassant(int move) {
        return flags(move) == EP_CAPTURE;
    }

    public static boolean isCastle(int move) {
        int flags = flags(move);
        return flags == CASTLE_KING || flags == CASTLE_QUEEN;
    }

    public static boolean isDoublePush(int move) {
        return flags(move) == DOUBLE_PUSH;
    }

    /** Meaningful only when {@link #isPromotion(int)} holds. */
    public static int promotionType(int move) {
        return Pieces.KNIGHT + (flags(move) & 3);
    }

    /** The move in UCI's long algebraic form, for example {@code e2e4} or {@code a7a8q}. */
    public static String toUci(int move) {
        StringBuilder out = new StringBuilder(5);
        out.append(Squares.name(from(move)));
        out.append(Squares.name(to(move)));
        if (isPromotion(move)) {
            out.append(Character.toLowerCase(Pieces.toChar(Pieces.of(Pieces.BLACK, promotionType(move)))));
        }
        return out.toString();
    }
}
