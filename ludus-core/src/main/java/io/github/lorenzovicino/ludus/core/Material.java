package io.github.lorenzovicino.ludus.core;

/**
 * Facts about the material on the board that are true regardless of how anything is evaluated.
 *
 * <p>This lives in {@code core} rather than {@code eval} because it is not a judgement. "Neither side
 * can deliver mate" is a property of the position, like the legality of a move, and two places need it:
 * the evaluation, which must score such a position level, and the training pipeline, which must not
 * label it as anything else. One implementation, so they cannot drift apart.
 */
public final class Material {

    private Material() {
    }

    /**
     * Whether neither side has the material to deliver mate, making the position drawn whatever the
     * pieces are doing.
     *
     * <p>The condition is the conservative one: no pawns, no rooks, no queens, and at most one minor
     * piece each. With two minors on the board split one apiece, no mate exists to be found.
     *
     * <p>Deliberately excluded: <strong>two knights against a bare king</strong>, and any position
     * where one side holds two bishops or a bishop and knight. The latter two are real wins. Two
     * knights cannot force mate against correct defence, but "cannot be forced" and "is a draw" are
     * different claims and only the second one justifies calling a position level.
     */
    public static boolean isInsufficientToMate(Board board) {
        if (board.pieces(Pieces.WHITE, Pieces.PAWN) != 0
                || board.pieces(Pieces.BLACK, Pieces.PAWN) != 0
                || board.pieces(Pieces.WHITE, Pieces.ROOK) != 0
                || board.pieces(Pieces.BLACK, Pieces.ROOK) != 0
                || board.pieces(Pieces.WHITE, Pieces.QUEEN) != 0
                || board.pieces(Pieces.BLACK, Pieces.QUEEN) != 0) {
            return false;
        }
        return minorCount(board, Pieces.WHITE) <= 1 && minorCount(board, Pieces.BLACK) <= 1;
    }

    private static int minorCount(Board board, int color) {
        return Bitboards.count(board.pieces(color, Pieces.KNIGHT))
                + Bitboards.count(board.pieces(color, Pieces.BISHOP));
    }
}
