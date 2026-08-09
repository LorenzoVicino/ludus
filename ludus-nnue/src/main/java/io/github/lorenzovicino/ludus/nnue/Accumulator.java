package io.github.lorenzovicino.ludus.nnue;

import io.github.lorenzovicino.ludus.core.Bitboards;
import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.Pieces;

/**
 * The first layer's output, carried forward instead of recomputed.
 *
 * <p>This is where the E in NNUE lives. The feature transformer is the expensive part — 768 into 256,
 * twice — but between a node and its child almost nothing changes: an ordinary move takes one piece
 * off one square and puts it on another, which is two columns of weights out of 768. So the layer is
 * not recalculated, it is adjusted.
 *
 * <p>Undoing is free. Each level of the stack holds the state after one more move, so unmaking is a
 * decrement — half the reason the scheme is worth the complexity.
 *
 * <p>The whole thing rests on one property: the value carried forward must be <em>identical</em> to a
 * full recomputation, not merely close. A drift here breaks nothing loudly; it just makes the engine
 * play slightly worse for reasons no log will show. {@link #computeFresh} exists so a test can hold
 * one against the other at every node of a game.
 */
final class Accumulator {

    /** Deeper than search and quiescence together can go. */
    private static final int MAX_PLY = 256;

    private final NnueNetwork network;
    private final short[][][] levels = new short[MAX_PLY][Pieces.COLOR_COUNT][NnueNetwork.HIDDEN];
    private int ply;

    Accumulator(NnueNetwork network) {
        this.network = network;
    }

    /** Rebuilds level zero from the position and forgets everything above it. */
    void reset(Board board) {
        ply = 0;
        computeInto(board, levels[0]);
    }

    short[] perspective(int color) {
        return levels[ply][color];
    }

    int ply() {
        return ply;
    }

    /**
     * Advances to the position {@code move} produces.
     *
     * <p>Called with {@code board} still in its pre-move state, which is the only moment the captured
     * piece is still visible — its identity and square are exactly what the delta needs.
     */
    void push(Board board, int move) {
        if (ply + 1 >= MAX_PLY) {
            throw new IllegalStateException("Accumulator stack exhausted at ply " + ply);
        }

        short[][] from = levels[ply];
        short[][] to = levels[ply + 1];
        System.arraycopy(from[Pieces.WHITE], 0, to[Pieces.WHITE], 0, NnueNetwork.HIDDEN);
        System.arraycopy(from[Pieces.BLACK], 0, to[Pieces.BLACK], 0, NnueNetwork.HIDDEN);
        ply++;

        int origin = Move.from(move);
        int destination = Move.to(move);
        int us = board.sideToMove();
        int them = Pieces.flip(us);
        int movedType = Pieces.typeOf(board.pieceAt(origin));

        remove(us, movedType, origin);

        if (Move.isEnPassant(move)) {
            // The captured pawn is not on the destination square. This is the case that breaks
            // every implementation of this exactly once.
            int victimSquare = us == Pieces.WHITE ? destination - 8 : destination + 8;
            remove(them, Pieces.PAWN, victimSquare);
        } else if (Move.isCapture(move)) {
            remove(them, Pieces.typeOf(board.pieceAt(destination)), destination);
        }

        int arrivingType = Move.isPromotion(move) ? Move.promotionType(move) : movedType;
        add(us, arrivingType, destination);

        int flags = Move.flags(move);
        if (flags == Move.CASTLE_KING) {
            remove(us, Pieces.ROOK, destination + 1);
            add(us, Pieces.ROOK, destination - 1);
        } else if (flags == Move.CASTLE_QUEEN) {
            remove(us, Pieces.ROOK, destination - 2);
            add(us, Pieces.ROOK, destination + 1);
        }
    }

    void pop() {
        if (ply == 0) {
            throw new IllegalStateException("Accumulator popped below its root");
        }
        ply--;
    }

    private void add(int color, int type, int square) {
        for (int perspective = 0; perspective < Pieces.COLOR_COUNT; perspective++) {
            int base = NnueNetwork.featureIndex(perspective, color, type, square) * NnueNetwork.HIDDEN;
            short[] target = levels[ply][perspective];
            for (int i = 0; i < NnueNetwork.HIDDEN; i++) {
                target[i] += network.featureWeights[base + i];
            }
        }
    }

    private void remove(int color, int type, int square) {
        for (int perspective = 0; perspective < Pieces.COLOR_COUNT; perspective++) {
            int base = NnueNetwork.featureIndex(perspective, color, type, square) * NnueNetwork.HIDDEN;
            short[] target = levels[ply][perspective];
            for (int i = 0; i < NnueNetwork.HIDDEN; i++) {
                target[i] -= network.featureWeights[base + i];
            }
        }
    }

    /** The layer computed from the position alone. The reference the incremental value must match. */
    short[][] computeFresh(Board board) {
        short[][] fresh = new short[Pieces.COLOR_COUNT][NnueNetwork.HIDDEN];
        computeInto(board, fresh);
        return fresh;
    }

    private void computeInto(Board board, short[][] target) {
        for (int perspective = 0; perspective < Pieces.COLOR_COUNT; perspective++) {
            System.arraycopy(network.featureBiases, 0, target[perspective], 0, NnueNetwork.HIDDEN);
        }
        for (int color = 0; color < Pieces.COLOR_COUNT; color++) {
            for (int type = 0; type < Pieces.TYPE_COUNT; type++) {
                long pieces = board.pieces(color, type);
                while (pieces != 0) {
                    int square = Bitboards.lsb(pieces);
                    pieces = Bitboards.popLsb(pieces);
                    for (int perspective = 0; perspective < Pieces.COLOR_COUNT; perspective++) {
                        int base = NnueNetwork.featureIndex(perspective, color, type, square)
                                * NnueNetwork.HIDDEN;
                        short[] into = target[perspective];
                        for (int i = 0; i < NnueNetwork.HIDDEN; i++) {
                            into[i] += network.featureWeights[base + i];
                        }
                    }
                }
            }
        }
    }
}
