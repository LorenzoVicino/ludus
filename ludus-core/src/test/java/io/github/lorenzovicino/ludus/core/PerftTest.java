package io.github.lorenzovicino.ludus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The correctness gate for move generation. Nothing else in the engine is worth trusting until
 * this passes — see DESIGN.md §9, M0.
 */
class PerftTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("fastCases")
    void matchesPublishedNodeCount(PerftSuite.Case testCase) {
        Board board = Board.fromFen(testCase.fen());
        long nodes = new Perft().count(board, testCase.depth());
        assertEquals(testCase.nodes(), nodes,
                () -> "Perft mismatch for " + testCase.name() + " at depth " + testCase.depth()
                        + ".\nFEN: " + testCase.fen()
                        + "\nRun Perft.divide at this depth and compare per-move counts against a"
                        + " reference engine to find the offending branch.");
    }

    static List<PerftSuite.Case> fastCases() {
        return PerftSuite.fast();
    }

    @Test
    void depthZeroCountsThePositionItself() {
        assertEquals(1, new Perft().count(Board.startPosition(), 0));
    }

    @Test
    void divideAccountsForEveryNode() {
        Board board = Board.startPosition();
        Perft perft = new Perft();
        long total = perft.divide(board, 3).values().stream().mapToLong(Long::longValue).sum();
        assertEquals(perft.count(Board.startPosition(), 3), total,
                "Divide must partition the same tree that count walks");
    }

    @Test
    void divideNamesTwentyOpeningMoves() {
        assertEquals(20, new Perft().divide(Board.startPosition(), 1).size());
    }
}
