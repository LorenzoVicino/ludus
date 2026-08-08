package io.github.lorenzovicino.ludus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The full perft suite, deep entries included. Minutes rather than seconds, so it runs nightly
 * and via {@code gradlew slowTest} — not on every push.
 */
@Tag("slow")
class PerftDeepTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("allCases")
    void matchesPublishedNodeCount(PerftSuite.Case testCase) {
        Board board = Board.fromFen(testCase.fen());
        long start = System.nanoTime();
        long nodes = new Perft().count(board, testCase.depth());
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(testCase.nodes(), nodes,
                () -> "Perft mismatch for " + testCase.name() + " at depth " + testCase.depth()
                        + ".\nFEN: " + testCase.fen());

        long nps = millis == 0 ? nodes : nodes * 1000 / millis;
        System.out.printf("%-12s depth %d  %,15d nodes  %6d ms  %,d nodes/s%n",
                testCase.name(), testCase.depth(), nodes, millis, nps);
    }

    static List<PerftSuite.Case> allCases() {
        return PerftSuite.all();
    }
}
