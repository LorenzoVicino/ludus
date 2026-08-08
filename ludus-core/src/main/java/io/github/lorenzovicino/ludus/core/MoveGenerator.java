package io.github.lorenzovicino.ludus.core;

/**
 * Pseudo-legal move generation: every move that follows the movement rules, including those that
 * leave or place one's own king in check. Legality is decided afterwards, by making the move and
 * asking whether the king is attacked.
 *
 * <p>This is stage one of the two-stage plan in DESIGN.md §4.2, and the staging is the point.
 * Filtering after the fact is slower than generating only legal moves, but it is very hard to get
 * wrong, so it produces a correct perft quickly — and that correct perft is the oracle against
 * which the fast direct generator will be validated at M3. The slow code exists to validate the
 * fast code.
 *
 * <p>It also gets en passant right for free. A capture that would expose one's own king along the
 * fifth rank is rejected because the move is actually played, removing both pawns, before the
 * position is examined. A direct generator has to reason about that case explicitly, and that is
 * the case everybody's perft fails on.
 */
public final class MoveGenerator {

    private MoveGenerator() {
    }

    /** No legal chess position has more than 218 moves; the margin is free. */
    public static final int MAX_MOVES = 256;

    /** @return the number of moves written to {@code moves}, starting at index 0. */
    public static int generate(Board board, int[] moves) {
        int us = board.sideToMove();
        int them = Pieces.flip(us);
        long ours = board.byColor(us);
        long theirs = board.byColor(them);
        long occupied = board.occupied();

        int count = 0;
        count = pawnMoves(board, us, them, theirs, occupied, moves, count);
        count = stepMoves(board, us, Pieces.KNIGHT, ours, theirs, moves, count);
        count = sliderMoves(board, us, Pieces.BISHOP, ours, theirs, occupied, moves, count);
        count = sliderMoves(board, us, Pieces.ROOK, ours, theirs, occupied, moves, count);
        count = sliderMoves(board, us, Pieces.QUEEN, ours, theirs, occupied, moves, count);
        count = stepMoves(board, us, Pieces.KING, ours, theirs, moves, count);
        count = castlingMoves(board, us, them, moves, count);
        return count;
    }

    /**
     * Compacts a pseudo-legal list down to the legal moves, in place.
     *
     * <p>Allocation-free, but it makes and unmakes every move, so it belongs to tools and tests.
     * The search interleaves the same check with its recursion instead of paying for a separate
     * pass.
     *
     * @return the number of legal moves now at the front of {@code moves}
     */
    public static int filterLegal(Board board, int[] moves, int count) {
        int us = board.sideToMove();
        int kept = 0;
        for (int i = 0; i < count; i++) {
            int move = moves[i];
            board.makeMove(move);
            boolean legal = !board.isKingAttacked(us);
            board.unmakeMove(move);
            if (legal) {
                moves[kept++] = move;
            }
        }
        return kept;
    }

    private static int pawnMoves(Board board, int us, int them, long theirs, long occupied,
                                 int[] moves, int count) {
        long pawns = board.pieces(us, Pieces.PAWN);
        if (pawns == 0) {
            return count;
        }
        long empty = ~occupied;
        boolean white = us == Pieces.WHITE;
        int up = white ? 8 : -8;
        long promotionRank = white ? Bitboards.RANK_8 : Bitboards.RANK_1;
        // Where a pawn lands after a single push from its start rank. A double push is legal only
        // if this intermediate square is empty too.
        long stepRank = white ? Bitboards.RANK_3 : Bitboards.RANK_6;

        long pushes = (white ? pawns << 8 : pawns >>> 8) & empty;
        long doublePushes = (white ? (pushes & stepRank) << 8 : (pushes & stepRank) >>> 8) & empty;

        long quiet = pushes & ~promotionRank;
        while (quiet != 0) {
            int to = Bitboards.lsb(quiet);
            quiet = Bitboards.popLsb(quiet);
            moves[count++] = Move.of(to - up, to, Move.QUIET);
        }

        long promoting = pushes & promotionRank;
        while (promoting != 0) {
            int to = Bitboards.lsb(promoting);
            promoting = Bitboards.popLsb(promoting);
            int from = to - up;
            // All four, not just the queen: underpromotion to a knight or rook avoids handing the
            // opponent a stalemate often enough to matter.
            moves[count++] = Move.of(from, to, Move.PROMO_QUEEN);
            moves[count++] = Move.of(from, to, Move.PROMO_ROOK);
            moves[count++] = Move.of(from, to, Move.PROMO_BISHOP);
            moves[count++] = Move.of(from, to, Move.PROMO_KNIGHT);
        }

        while (doublePushes != 0) {
            int to = Bitboards.lsb(doublePushes);
            doublePushes = Bitboards.popLsb(doublePushes);
            moves[count++] = Move.of(to - 2 * up, to, Move.DOUBLE_PUSH);
        }

        long remaining = pawns;
        while (remaining != 0) {
            int from = Bitboards.lsb(remaining);
            remaining = Bitboards.popLsb(remaining);
            long targets = Attacks.pawn(us, from) & theirs;
            while (targets != 0) {
                int to = Bitboards.lsb(targets);
                targets = Bitboards.popLsb(targets);
                if (Bitboards.contains(promotionRank, to)) {
                    moves[count++] = Move.of(from, to, Move.PROMO_CAPTURE_QUEEN);
                    moves[count++] = Move.of(from, to, Move.PROMO_CAPTURE_ROOK);
                    moves[count++] = Move.of(from, to, Move.PROMO_CAPTURE_BISHOP);
                    moves[count++] = Move.of(from, to, Move.PROMO_CAPTURE_KNIGHT);
                } else {
                    moves[count++] = Move.of(from, to, Move.CAPTURE);
                }
            }
        }

        int epSquare = board.epSquare();
        if (epSquare != Squares.NONE) {
            // Our pawns that could capture onto the en passant square are exactly those standing
            // where an enemy pawn on that square would attack.
            long capturers = Attacks.pawn(them, epSquare) & pawns;
            while (capturers != 0) {
                int from = Bitboards.lsb(capturers);
                capturers = Bitboards.popLsb(capturers);
                moves[count++] = Move.of(from, epSquare, Move.EP_CAPTURE);
            }
        }
        return count;
    }

    private static int stepMoves(Board board, int us, int type, long ours, long theirs,
                                 int[] moves, int count) {
        long from = board.pieces(us, type);
        while (from != 0) {
            int square = Bitboards.lsb(from);
            from = Bitboards.popLsb(from);
            long attacks = type == Pieces.KNIGHT ? Attacks.knight(square) : Attacks.king(square);
            count = emit(square, attacks & ~ours, theirs, moves, count);
        }
        return count;
    }

    private static int sliderMoves(Board board, int us, int type, long ours, long theirs,
                                   long occupied, int[] moves, int count) {
        long from = board.pieces(us, type);
        while (from != 0) {
            int square = Bitboards.lsb(from);
            from = Bitboards.popLsb(from);
            count = emit(square, Attacks.slider(type, square, occupied) & ~ours, theirs, moves, count);
        }
        return count;
    }

    private static int emit(int from, long targets, long theirs, int[] moves, int count) {
        while (targets != 0) {
            int to = Bitboards.lsb(targets);
            targets = Bitboards.popLsb(targets);
            int flags = Bitboards.contains(theirs, to) ? Move.CAPTURE : Move.QUIET;
            moves[count++] = Move.of(from, to, flags);
        }
        return count;
    }

    private static int castlingMoves(Board board, int us, int them, int[] moves, int count) {
        int rights = board.castlingRights();
        if (rights == Castling.NONE) {
            return count;
        }
        int king = us == Pieces.WHITE ? Squares.E1 : Squares.E8;

        // Three separate conditions, all of them real: the squares between must be empty, the king
        // must not currently be in check, and it must not pass through an attacked square. The
        // destination is left to the legality filter, which rejects any move that leaves the king
        // attacked.
        if ((rights & Castling.kingSide(us)) != 0
                && board.isEmpty(king + 1)
                && board.isEmpty(king + 2)
                && !board.isAttacked(king, them)
                && !board.isAttacked(king + 1, them)) {
            moves[count++] = Move.of(king, king + 2, Move.CASTLE_KING);
        }
        if ((rights & Castling.queenSide(us)) != 0
                && board.isEmpty(king - 1)
                && board.isEmpty(king - 2)
                && board.isEmpty(king - 3)
                && !board.isAttacked(king, them)
                && !board.isAttacked(king - 1, them)) {
            moves[count++] = Move.of(king, king - 2, Move.CASTLE_QUEEN);
        }
        return count;
    }
}
