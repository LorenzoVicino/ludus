package io.github.lorenzovicino.ludus.search;

import io.github.lorenzovicino.ludus.core.Move;

/**
 * The outcome of a search.
 *
 * @param bestMove  the move to play, or {@link Move#NONE} only when the position has none
 * @param score     centipawns from the searching side's point of view, or a mate score
 * @param nodes     nodes visited
 * @param depth     the deepest iteration that ran to completion
 */
public record SearchResult(int bestMove, int score, long nodes, int depth) {

    public boolean hasMove() {
        return bestMove != Move.NONE;
    }
}
