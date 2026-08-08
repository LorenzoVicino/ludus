package io.github.lorenzovicino.ludus.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Counts the leaves of the legal move tree.
 *
 * <p>Published counts exist for standard positions, so a mismatch is proof of a move generation
 * bug rather than a hint — and {@link #divide(Board, int)} narrows it to a single root move,
 * which makes this a debugger and not just a test. See DESIGN.md §1.1.
 *
 * <p>Instances hold one move buffer per depth so the recursion allocates nothing.
 */
public final class Perft {

    private static final int MAX_DEPTH = 32;

    private final int[][] buffers = new int[MAX_DEPTH][MoveGenerator.MAX_MOVES];

    public long count(Board board, int depth) {
        if (depth < 0 || depth >= MAX_DEPTH) {
            throw new IllegalArgumentException("Depth must be in 0.." + (MAX_DEPTH - 1) + ", got " + depth);
        }
        return perft(board, depth);
    }

    private long perft(Board board, int depth) {
        if (depth == 0) {
            return 1;
        }
        int[] moves = buffers[depth];
        int count = MoveGenerator.generate(board, moves);
        int us = board.sideToMove();
        long nodes = 0;
        for (int i = 0; i < count; i++) {
            int move = moves[i];
            board.makeMove(move);
            if (!board.isKingAttacked(us)) {
                nodes += perft(board, depth - 1);
            }
            board.unmakeMove(move);
        }
        return nodes;
    }

    /**
     * Node count per legal root move, in generation order.
     *
     * <p>This is what turns a wrong total into a located bug: compare against a reference engine's
     * divide, find the one move whose subtree differs, play it, and repeat. Each step drops a
     * depth, so a bug at depth 6 is cornered in six comparisons.
     */
    public Map<String, Long> divide(Board board, int depth) {
        if (depth < 1) {
            throw new IllegalArgumentException("Divide needs depth >= 1, got " + depth);
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.generate(board, moves);
        int us = board.sideToMove();
        for (int i = 0; i < count; i++) {
            int move = moves[i];
            board.makeMove(move);
            if (!board.isKingAttacked(us)) {
                counts.put(Move.toUci(move), perft(board, depth - 1));
            }
            board.unmakeMove(move);
        }
        return counts;
    }
}
