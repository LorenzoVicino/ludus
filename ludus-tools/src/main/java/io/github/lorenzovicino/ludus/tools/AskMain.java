package io.github.lorenzovicino.ludus.tools;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
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
 * What move does it play, and what does it think, with each evaluation at equal depth.
 *
 * <pre>
 * java -jar ludus-match.jar ask --depth 8 --nnue build/ludus.nnue
 * </pre>
 *
 * <h2>Why this is not done over UCI</h2>
 *
 * <p>Because three attempts at driving the engine's own protocol from a shell measured the harness
 * instead of the engine. Redirecting a file into it makes the search stop early — end of input is
 * treated as {@code quit}, so it reports whatever depth it had reached rather than the depth asked for.
 * Keeping the pipe open from a script then deadlocked: the engine blocked writing to a full stdout while
 * the script blocked reading for a line that had already gone past.
 *
 * <p>Calling the search directly has neither problem, and it removes the protocol from the measurement
 * entirely — which is what was wanted anyway. A match is what exercises UCI.
 *
 * <p>Equal depth rather than equal time, deliberately. At equal time a slower evaluation searches less
 * and the two differences — playing worse and running slower — arrive mixed together. At equal depth
 * only the evaluation differs, so a bad move is the evaluation's fault and nothing else's.
 */
public final class AskMain {

    private static final List<String> DEFAULT_POSITIONS = List.of(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 4 4",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "6k1/5ppp/8/8/8/8/5PPP/R5K1 b - - 0 1",
            "8/5k2/3p4/1p1Pp2p/pP2Pp1P/P4P1K/8/8 b - - 0 1",
            "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1");

    private AskMain() {
    }

    public static int run(String[] args) throws Exception {
        int depth = 8;
        Path network = null;
        String single = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--depth" -> depth = Integer.parseInt(value(args, ++i, "--depth"));
                case "--nnue" -> network = Path.of(value(args, ++i, "--nnue"));
                case "--fen" -> single = value(args, ++i, "--fen");
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        List<String> positions = single == null ? DEFAULT_POSITIONS : List.of(single);

        Evaluator hand = new HandCraftedEvaluator();
        Evaluator learned = network == null ? null : new NnueEvaluator(NnueNetwork.load(network));

        System.out.printf(Locale.ROOT, "depth %d, both evaluations on the same positions%n%n", depth);
        System.out.printf(Locale.ROOT, "%-46s %18s %18s%n", "position", "hand-written", "network");

        int agreed = 0;
        int compared = 0;

        for (String fen : positions) {
            Answer handAnswer = ask(hand, fen, depth);
            String left = handAnswer.toString();
            String right = "-";

            if (learned != null) {
                Answer learnedAnswer = ask(learned, fen, depth);
                right = learnedAnswer.toString();
                compared++;
                if (handAnswer.move.equals(learnedAnswer.move)) {
                    agreed++;
                }
            }

            System.out.printf(Locale.ROOT, "%-46s %18s %18s%n",
                    fen.substring(0, Math.min(44, fen.length())), left, right);
        }

        if (compared > 0) {
            System.out.printf(Locale.ROOT, "%nsame move in %d of %d positions%n", agreed, compared);
            System.out.println("A move both agree on says nothing either way; a move only one of them");
            System.out.println("plays is where the evaluations actually differ, and worth looking at.");
        }
        return 0;
    }

    private record Answer(String move, int score, long nodes) {
        @Override
        public String toString() {
            return "%s %+d/%dk".formatted(move, score, nodes / 1000);
        }
    }

    private static Answer ask(Evaluator evaluator, String fen, int depth) {
        Search search = new Search(evaluator);
        search.newGame();
        search.clearStop();
        SearchResult result = search.search(Board.fromFen(fen), SearchLimits.depth(depth));
        return new Answer(Move.toUci(result.bestMove()), result.score(), result.nodes());
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
