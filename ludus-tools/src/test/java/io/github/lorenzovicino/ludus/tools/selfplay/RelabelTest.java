package io.github.lorenzovicino.ludus.tools.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tool that rewrites training data has to be held to the same standard as the data.
 *
 * <p>The two ways it could go wrong are opposite and both silent: rewriting a label it had no business
 * touching, or dropping lines it could not read. The second is the worse of the two, because a dataset
 * that quietly shrank still trains.
 */
class RelabelTest {

    private static final String DEAD_DRAW = "8/8/4k3/8/8/2KB4/8/8 w - - 0 1|368|1";
    private static final String WON_ENDGAME = "8/8/4k3/8/8/2KR4/8/8 w - - 0 1|568|2";
    private static final String MIDDLEGAME =
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1|-42|0";
    private static final String PAWN_ENDING = "8/8/4k3/8/4P3/2K5/8/8 w - - 0 1|170|2";

    private List<String> relabel(@TempDir Path directory, List<String> input) throws Exception {
        Path in = directory.resolve("in.txt");
        Path out = directory.resolve("out.txt");
        Files.write(in, input, StandardCharsets.UTF_8);
        assertEquals(0, RelabelMain.run(new String[] {"--in", in.toString(), "--out", out.toString()}));
        return Files.readAllLines(out, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a dead draw is rewritten to zero and nothing else is")
    void onlyDeadDrawsAreRewritten(@TempDir Path directory) throws Exception {
        List<String> result = relabel(directory,
                List.of(DEAD_DRAW, WON_ENDGAME, MIDDLEGAME, PAWN_ENDING));

        assertEquals(4, result.size(), "no line may be lost");
        assertTrue(result.get(0).endsWith("|0|1"), "the dead draw should read zero: " + result.get(0));
        assertEquals(WON_ENDGAME, result.get(1), "a won endgame must be untouched");
        assertEquals(MIDDLEGAME, result.get(2), "a middlegame must be untouched");
        assertEquals(PAWN_ENDING, result.get(3), "a pawn can promote, so this is not a draw");
    }

    @Test
    @DisplayName("the game result is preserved, only the score changes")
    void theResultIsNotTouched(@TempDir Path directory) throws Exception {
        List<String> result = relabel(directory, List.of(DEAD_DRAW));
        SelfPlaySample sample = SelfPlaySample.decode(result.get(0));
        assertEquals(0, sample.score());
        assertEquals(SelfPlaySample.DRAW, sample.result());
        assertEquals("8/8/4k3/8/8/2KB4/8/8 w - - 0 1", sample.fen());
    }

    @Test
    @DisplayName("a line it cannot parse is passed through, not dropped")
    void unparseableLinesSurvive(@TempDir Path directory) throws Exception {
        List<String> result = relabel(directory, List.of("this is not a sample", DEAD_DRAW));
        assertEquals(2, result.size());
        assertEquals("this is not a sample", result.get(0),
                "silently shrinking a dataset is worse than leaving a bad line in it");
    }

    @Test
    @DisplayName("running it twice changes nothing the second time")
    void theOperationIsIdempotent(@TempDir Path directory) throws Exception {
        List<String> once = relabel(directory, List.of(DEAD_DRAW, WON_ENDGAME, MIDDLEGAME));
        Path second = directory.resolve("second");
        Files.createDirectories(second);
        List<String> twice = relabel(second, once);
        assertEquals(once, twice);
    }

    @Test
    @DisplayName("both paths are required rather than guessed")
    void bothPathsAreRequired() {
        assertThrows(IllegalArgumentException.class, () -> RelabelMain.run(new String[] {"--in", "x"}));
        assertThrows(IllegalArgumentException.class, () -> RelabelMain.run(new String[] {"--out", "x"}));
    }
}
