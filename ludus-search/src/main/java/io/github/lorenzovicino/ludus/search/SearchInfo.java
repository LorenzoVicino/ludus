package io.github.lorenzovicino.ludus.search;

/**
 * One completed iteration, reported so a host can print a UCI {@code info} line.
 *
 * @param depth          the iteration that produced this
 * @param score          centipawns from the searching side's point of view, or a mate score
 * @param nodes          nodes visited in the whole search so far
 * @param elapsedMillis  wall time since the search began
 * @param pv             the principal variation, best move first
 */
public record SearchInfo(int depth, int score, long nodes, long elapsedMillis, int[] pv) {

    public long nodesPerSecond() {
        return elapsedMillis <= 0 ? nodes : nodes * 1000 / elapsedMillis;
    }
}
