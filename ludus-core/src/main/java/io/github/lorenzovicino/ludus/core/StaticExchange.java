package io.github.lorenzovicino.ludus.core;

/**
 * Static exchange evaluation: what a capture is worth once both sides have taken turns recapturing
 * on the same square until neither wants to continue.
 *
 * <p>It answers the question a plain victim-minus-attacker comparison cannot. Taking a defended
 * pawn with a queen looks like winning a pawn and is actually losing a queen; SEE reports −850
 * rather than +100. The search uses that twice over — to sort losing captures behind quiet moves,
 * and to refuse to search them at all in quiescence.
 *
 * <p>X-rays fall out for free because the attacker set is recomputed against the shrinking
 * occupancy each round: a rook behind a bishop that has just captured is simply found on the next
 * pass. Slower than maintaining the set incrementally and far harder to get wrong.
 *
 * <p>Instances are not thread-safe: the swap list is preallocated so the search allocates nothing.
 *
 * <p>One known limitation, worth stating rather than hiding: the king is treated as an ordinary
 * attacker with a very large value. A king recapture into a defended square is illegal, so a
 * sequence ending that way can be scored wrongly. The large value makes the pruning cut off before
 * it matters in practice, and this is only used for ordering decisions.
 */
public final class StaticExchange {

    /**
     * Exchange values, not evaluation values. They are deliberately independent of
     * {@code HandCraftedEvaluator}: this is about who wins a trade, and it must keep answering that
     * question the same way when the evaluation is replaced by a neural network.
     */
    private static final int[] VALUE = {100, 320, 330, 500, 950, 20_000};

    /** Longer than any possible exchange: every capture removes a piece and there are 32. */
    private final int[] swap = new int[36];

    /** @return the centipawn outcome of {@code move}, or 0 if it captures nothing. */
    public int evaluate(Board board, int move) {
        int from = Move.from(move);
        int to = Move.to(move);

        int capturedType;
        if (Move.isEnPassant(move)) {
            capturedType = Pieces.PAWN;
        } else if (Move.isCapture(move)) {
            capturedType = Pieces.typeOf(board.pieceAt(to));
        } else if (Move.isPromotion(move)) {
            capturedType = -1;
        } else {
            return 0;
        }

        long occupied = board.occupied() & ~Bitboards.bit(from);
        if (Move.isEnPassant(move)) {
            int victimSquare = board.sideToMove() == Pieces.WHITE ? to - 8 : to + 8;
            occupied &= ~Bitboards.bit(victimSquare);
        }

        int gained = capturedType < 0 ? 0 : VALUE[capturedType];
        int attackerType = Pieces.typeOf(board.pieceAt(from));
        if (Move.isPromotion(move)) {
            // The pawn is gone and something better stands there, so both the immediate gain and
            // what the opponent can win back change.
            gained += VALUE[Move.promotionType(move)] - VALUE[Pieces.PAWN];
            attackerType = Move.promotionType(move);
        }

        int depth = 0;
        swap[0] = gained;
        int side = Pieces.flip(board.sideToMove());

        while (depth < swap.length - 2) {
            depth++;
            swap[depth] = VALUE[attackerType] - swap[depth - 1];

            // Neither side would enter this continuation, so it never happens.
            if (Math.max(-swap[depth - 1], swap[depth]) < 0) {
                break;
            }

            long attackers = attackersTo(board, to, occupied) & board.byColor(side);
            int next = leastValuable(board, attackers);
            if (next == Squares.NONE) {
                break;
            }
            occupied &= ~Bitboards.bit(next);
            attackerType = Pieces.typeOf(board.pieceAt(next));
            side = Pieces.flip(side);
        }

        // Fold the sequence back: at each step the side to move takes the exchange only if it beats
        // standing pat, which is what the negation of the maximum expresses.
        while (depth > 1) {
            depth--;
            swap[depth - 1] = -Math.max(-swap[depth - 1], swap[depth]);
        }
        return swap[0];
    }

    /** Every piece of either colour that attacks {@code square} under {@code occupied}. */
    private static long attackersTo(Board board, int square, long occupied) {
        long attackers = 0;
        attackers |= Attacks.pawn(Pieces.BLACK, square) & board.pieces(Pieces.WHITE, Pieces.PAWN);
        attackers |= Attacks.pawn(Pieces.WHITE, square) & board.pieces(Pieces.BLACK, Pieces.PAWN);
        attackers |= Attacks.knight(square) & board.byType(Pieces.KNIGHT);
        attackers |= Attacks.king(square) & board.byType(Pieces.KING);
        attackers |= Attacks.bishop(square, occupied)
                & (board.byType(Pieces.BISHOP) | board.byType(Pieces.QUEEN));
        attackers |= Attacks.rook(square, occupied)
                & (board.byType(Pieces.ROOK) | board.byType(Pieces.QUEEN));
        // Pieces already spent in the exchange are off `occupied` and must not attack again.
        return attackers & occupied;
    }

    private static int leastValuable(Board board, long attackers) {
        for (int type = Pieces.PAWN; type <= Pieces.KING; type++) {
            long candidates = attackers & board.byType(type);
            if (candidates != 0) {
                return Bitboards.lsb(candidates);
            }
        }
        return Squares.NONE;
    }
}
