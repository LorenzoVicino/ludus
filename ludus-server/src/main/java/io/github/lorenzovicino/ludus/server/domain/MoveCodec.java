package io.github.lorenzovicino.ludus.server.domain;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the strings a browser sends into the packed integers the engine speaks, and back.
 *
 * <h2>Why parsing means generating</h2>
 *
 * <p>There is no attempt to decode {@code "e7e8q"} into a move by taking it apart. The legal moves of
 * the position are generated and the string is matched against them. That is slower and it is the only
 * version that cannot be wrong: a move is legal or it does not exist, and the same lookup that finds it
 * proves it. Decoding by hand would have to re-derive castling, en passant and promotion rules that
 * {@link MoveGenerator} already implements, and a second implementation of a rule is a second chance to
 * get it wrong.
 *
 * <p>It also means an unknown move and an illegal move fail identically, which is correct: from
 * outside, "you may not play that" is one answer.
 */
public final class MoveCodec {

    private MoveCodec() {
    }

    /**
     * @throws IllegalMoveException if the position has no such legal move
     */
    public static int parse(Board board, String uci) {
        String wanted = uci == null ? "" : uci.trim().toLowerCase();
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));

        for (int i = 0; i < count; i++) {
            if (Move.toUci(moves[i]).equals(wanted)) {
                return moves[i];
            }
        }
        throw new IllegalMoveException(wanted, legalMoves(board));
    }

    /** Every legal move in the position, as UCI strings. */
    public static List<String> legalMoves(Board board) {
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));

        List<String> uci = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            uci.add(Move.toUci(moves[i]));
        }
        return uci;
    }

    public static boolean hasLegalMove(Board board) {
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        return MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves)) > 0;
    }
}
