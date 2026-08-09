package io.github.lorenzovicino.ludus.tools;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.eval.Evaluator;
import io.github.lorenzovicino.ludus.eval.HandCraftedEvaluator;
import io.github.lorenzovicino.ludus.nnue.NnueEvaluator;
import io.github.lorenzovicino.ludus.nnue.NnueNetwork;
import io.github.lorenzovicino.ludus.search.Search;
import io.github.lorenzovicino.ludus.search.SearchLimits;
import io.github.lorenzovicino.ludus.search.SearchResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Searches a fixed position set to a fixed depth and reports how fast it went.
 *
 * <pre>
 * java -jar ludus-match.jar bench --depth 8
 * java -jar ludus-match.jar bench --depth 8 --nnue build/ludus.nnue
 * </pre>
 *
 * <p>This is the metric DESIGN.md §1.3 exists for: it separates the two ways a change can hurt.
 * <em>Searching worse</em> shows up as Elo falling with nodes per second unchanged; <em>searching
 * slower</em> shows up here. They are different bugs, and without this measurement they look
 * identical from a match result.
 *
 * <p>Fixed depth rather than fixed time, deliberately. Fixed time would hide exactly what is being
 * measured: a slower engine would simply search less and report the same rate.
 */
public final class BenchMain {

    /** A spread of position types, so a change that only hurts endgames has somewhere to show. */
    private static final List<String> POSITIONS = List.of(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "8/5k2/3p4/1p1Pp2p/pP2Pp1P/P4P1K/8/8 b - - 0 1",
            "4rrk1/pp1n1pp1/2pb1q1p/3p4/3P1B2/2NBP2P/PPQ2PP1/R3R1K1 w - - 0 18",
            "2r3k1/1p3pp1/p2p3p/P1nPp3/1PP1P3/5PP1/2R3KP/2R5 b - - 0 30");

    private BenchMain() {
    }

    public static int run(String[] args) throws Exception {
        int depth = 8;
        Path network = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--depth" -> depth = Integer.parseInt(value(args, ++i, "--depth"));
                case "--nnue" -> network = Path.of(value(args, ++i, "--nnue"));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        Evaluator evaluator = network == null
                ? new HandCraftedEvaluator()
                : new NnueEvaluator(NnueNetwork.load(network));
        String label = network == null ? "hand-crafted" : "network " + network.getFileName();

        // One untimed pass first. Measuring a cold JVM measures the interpreter, not the engine.
        warmUp(evaluator, Math.min(depth, 6));

        Search search = new Search(evaluator);
        long nodes = 0;
        long start = System.nanoTime();

        for (String fen : POSITIONS) {
            Board board = Board.fromFen(fen);
            SearchResult result = search.search(board, SearchLimits.depth(depth));
            nodes += result.nodes();
        }

        long millis = (System.nanoTime() - start) / 1_000_000;
        long nps = millis == 0 ? nodes : nodes * 1000 / millis;

        System.out.printf(Locale.ROOT,
                "%-28s depth %d  %,12d nodes  %6d ms  %,10d nodes/s%n",
                label, depth, nodes, millis, nps);
        return 0;
    }

    private static void warmUp(Evaluator evaluator, int depth) {
        Search search = new Search(evaluator);
        for (String fen : POSITIONS) {
            search.search(Board.fromFen(fen), SearchLimits.depth(depth));
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
