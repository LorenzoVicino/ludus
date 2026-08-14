package io.github.lorenzovicino.ludus.server.domain;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Material;
import io.github.lorenzovicino.ludus.core.Pieces;

/**
 * Whether the game is over, and how.
 *
 * <p>All of it is asked of the engine's own board rather than reimplemented here. That is the point of
 * the arrangement: repetition, the fifty-move counter and insufficient material are already correct in
 * {@code ludus-core}, verified by the perft suite and by the material tests, and a second opinion living
 * in a web module would eventually disagree with the first.
 */
public final class Rules {

    private Rules() {
    }

    public static GameStatus statusOf(Board board) {
        if (!MoveCodec.hasLegalMove(board)) {
            if (board.inCheck()) {
                // The side with no reply is the side to move, so the other one delivered mate.
                return board.sideToMove() == Pieces.WHITE ? GameStatus.BLACK_WON : GameStatus.WHITE_WON;
            }
            return GameStatus.DRAW_STALEMATE;
        }
        if (Material.isInsufficientToMate(board)) {
            return GameStatus.DRAW_INSUFFICIENT_MATERIAL;
        }
        if (board.isFiftyMoveDraw()) {
            return GameStatus.DRAW_FIFTY_MOVE;
        }
        if (board.isRepetition()) {
            return GameStatus.DRAW_REPETITION;
        }
        return GameStatus.IN_PROGRESS;
    }
}
