package io.github.lorenzovicino.ludus.nnue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Checks that the engine evaluates a network the same way the trainer did.
 *
 * <p>Quantisation turns float weights into integers, and every constant in that conversion has a twin
 * on the other side. A wrong scale, a transposed layer, weights written in the wrong order — none of
 * these raise anything. They produce a network that plays worse than the one that was trained, for no
 * visible reason, and no other test in the project would notice.
 *
 * <p>So the exporter writes fixtures: positions with the float model's own answer in centipawns. This
 * asserts the integer path lands within the tolerance quantisation costs.
 *
 * <p>The fixtures are build output, not committed — training produces them. When they are absent the
 * test says so and skips, rather than failing a build on a machine where nobody has trained anything.
 */
class PyTorchAgreementTest {

    private static final Path NETWORK = Path.of("..", "build", "ludus.nnue");
    private static final Path FIXTURES = Path.of("..", "build", "nnue-fixtures.txt");

    /**
     * Quantisation is lossy by construction: weights are rounded to integers and activations are
     * clipped to 127 steps. A few centipawns of disagreement is the price. Tens would mean a wrong
     * scale rather than rounding.
     */
    private static final int TOLERANCE_CENTIPAWNS = 12;

    @Test
    void theEngineAgreesWithTheTrainerOnEveryFixture() throws IOException {
        Assumptions.assumeTrue(Files.exists(NETWORK) && Files.exists(FIXTURES),
                "No exported network to check. Run training/export.py first.");

        NnueNetwork network = NnueNetwork.load(NETWORK);
        NnueEvaluator evaluator = new NnueEvaluator(network);

        List<String> disagreements = new ArrayList<>();
        int checked = 0;
        int worst = 0;

        for (String line : Files.readAllLines(FIXTURES, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            int split = line.lastIndexOf('|');
            String fen = line.substring(0, split);
            int expected = Integer.parseInt(line.substring(split + 1).trim());

            Board board = Board.fromFen(fen);
            evaluator.reset(board);
            int actual = evaluator.evaluate(board);

            int difference = Math.abs(actual - expected);
            worst = Math.max(worst, difference);
            checked++;
            if (difference > TOLERANCE_CENTIPAWNS) {
                disagreements.add(String.format(
                        "%s%n    PyTorch %d, engine %d, off by %d", fen, expected, actual, difference));
            }
        }

        assertTrue(checked > 0, "The fixture file is empty");
        assertTrue(disagreements.isEmpty(),
                () -> "The quantised network does not match the trained one:\n"
                        + String.join("\n  ", disagreements));

        System.out.printf("engine matched PyTorch on %d positions, worst gap %d cp%n", checked, worst);
    }

    @Test
    void anExportedNetworkLoadsAndEvaluates() throws IOException {
        Assumptions.assumeTrue(Files.exists(NETWORK), "No exported network to load.");

        NnueNetwork network = NnueNetwork.load(NETWORK);
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Board board = Board.startPosition();
        evaluator.reset(board);

        int score = evaluator.evaluate(board);
        assertTrue(Math.abs(score) < 5_000,
                () -> "The opening position should not read as decided: " + score);
    }

    @Test
    void theIncrementalPathAgreesWithTheDirectOneOnARealNetwork() throws IOException {
        // The invariant test proves this for random weights. Repeating it on the trained network
        // costs nothing and closes the gap between "the arithmetic is right" and "the file we ship
        // is right".
        Assumptions.assumeTrue(Files.exists(NETWORK), "No exported network to check.");

        NnueNetwork network = NnueNetwork.load(NETWORK);
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Board board = Board.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        evaluator.reset(board);

        int direct = evaluator.evaluate(board);

        Board rebuilt = Board.fromFen(board.toFen());
        NnueEvaluator fresh = new NnueEvaluator(network);
        fresh.reset(rebuilt);

        assertEquals(fresh.evaluate(rebuilt), direct);
    }
}
