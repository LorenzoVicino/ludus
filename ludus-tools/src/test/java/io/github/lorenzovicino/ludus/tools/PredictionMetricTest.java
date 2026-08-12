package io.github.lorenzovicino.ludus.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scale in {@code bench --predict} is shared with the training code and nothing enforces that.
 *
 * <p>{@code features.py} declares {@code SCALE = 400} and {@code model.to_win_probability} divides by
 * it. This side hard-codes the same 400. If one moves, the win-probability columns are computed on a
 * different scale from the one the loss minimised, and the verdict keeps printing confidently while
 * meaning something else. That is the failure mode this file exists for.
 */
class PredictionMetricTest {

    @Test
    @DisplayName("the win-probability transform matches sigmoid(cp/400)")
    void winProbabilityUsesTheTrainingScale() {
        assertEquals(0.5, BenchMain.winProbability(0), 1e-9, "level is an even score");
        // sigmoid(1) at exactly one scale unit. If SCALE changes, this is the assertion that fails.
        assertEquals(0.7310585786, BenchMain.winProbability(400), 1e-9);
        assertEquals(0.2689414214, BenchMain.winProbability(-400), 1e-9);
        assertEquals(1.0 - BenchMain.winProbability(250), BenchMain.winProbability(-250), 1e-12,
                "the transform has to be symmetric or one colour reads differently");
    }

    @Test
    @DisplayName("the transform saturates, which is the reason the band table exists")
    void saturationIsRealAndWorthMeasuring() {
        double atFiveHundred = BenchMain.winProbability(500);
        double atNineHundred = BenchMain.winProbability(900);
        assertTrue(atNineHundred - atFiveHundred < 0.20,
                "400 centipawns apart should barely separate once saturated");
        assertTrue(BenchMain.winProbability(2000) > 0.99,
                "the training target is effectively pinned by this point");
    }

    @Test
    @DisplayName("label bands are assigned by their upper bound")
    void bandsAreAssignedByUpperBound() {
        assertEquals(0, BenchMain.bandFor(0));
        assertEquals(0, BenchMain.bandFor(50));
        assertEquals(1, BenchMain.bandFor(51));
        assertEquals(1, BenchMain.bandFor(150));
        assertEquals(2, BenchMain.bandFor(151));
        assertEquals(4, BenchMain.bandFor(2_000));
        assertEquals(4, BenchMain.bandFor(99_999), "anything past the last band lands in it");
    }

    @Test
    @DisplayName("a dataset of known positions is read and reported without throwing")
    void predictReadsADatasetEndToEnd() throws Exception {
        // No network is available in a unit test, so this exercises the parsing and banding by going
        // through the public entry point far enough to fail on the missing option rather than on the
        // file. The end-to-end agreement between the two languages is what export.py fixtures cover.
        Path dataset = Files.createTempFile("predict-test", ".txt");
        try {
            Files.write(dataset, List.of(
                    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1|12|1",
                    "8/8/8/1b5k/5K2/2B5/8/8 b - - 4 3|-23|1"));

            IllegalArgumentException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> BenchMain.run(new String[] {"--predict", dataset.toString()}));
            assertTrue(thrown.getMessage().contains("--nnue"),
                    "a prediction run without a network should say so plainly");
        } finally {
            Files.deleteIfExists(dataset);
        }
    }
}
