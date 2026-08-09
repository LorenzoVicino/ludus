package io.github.lorenzovicino.ludus.tools.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Pieces;
import io.github.lorenzovicino.ludus.search.Search;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SelfPlayTest {

    private static final String KIWIPETE =
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";

    @Test
    void aSampleSurvivesTheWireFormat() {
        SelfPlaySample sample = new SelfPlaySample(KIWIPETE, -137, SelfPlaySample.DRAW);
        assertEquals(sample, SelfPlaySample.decode(sample.encode()));
    }

    @Test
    void theResultIsAnIntegerSoNoLocaleCanTouchIt() {
        // Writing a draw as 0.5 through a default formatter on an Italian locale produces "0,5", and
        // a training file full of commas is a bug found days later, in Python.
        String encoded = new SelfPlaySample(KIWIPETE, 0, SelfPlaySample.DRAW).encode();
        assertTrue(encoded.endsWith("|1"), () -> "Expected an integer result: " + encoded);
        assertFalse(encoded.contains(","), () -> "No decimal separator should ever appear: " + encoded);
    }

    @Test
    void malformedSamplesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SelfPlaySample.decode("only|two"));
    }

    @Test
    void flippingASampleInvertsBothLabels() {
        // Score and result are both from the mover's point of view, so they have to flip together.
        // Flipping one and not the other is the mistake that trains fine and produces a network
        // convinced one colour is winning.
        SelfPlaySample won = new SelfPlaySample(KIWIPETE, 250, SelfPlaySample.WIN);
        SelfPlaySample lost = won.flipped(KIWIPETE);

        assertEquals(-250, lost.score());
        assertEquals(SelfPlaySample.LOSS, lost.result());
        assertEquals(SelfPlaySample.DRAW, new SelfPlaySample(KIWIPETE, 0, SelfPlaySample.DRAW)
                .flipped(KIWIPETE).result(), "A draw stays a draw from either side");
    }

    @Test
    void jobsSurviveTheWireFormat() {
        SelfPlayJob job = new SelfPlayJob(3, 12345L, 40, 6);
        assertEquals(job, SelfPlayJob.decode(job.encode()));
        assertThrows(IllegalArgumentException.class, () -> SelfPlayJob.decode("1|2"));
    }

    @Test
    void generatedSamplesAreQuietAndUsable() {
        List<SelfPlaySample> samples = new SelfPlayGenerator()
                .play("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 3);

        assertFalse(samples.isEmpty(), "A full game should yield something worth learning from");

        for (SelfPlaySample sample : samples) {
            Board board = Board.fromFen(sample.fen());

            assertFalse(board.inCheck(),
                    () -> "A position under check is not what the network is asked to judge: "
                            + sample.fen());
            assertFalse(Search.isMateScore(sample.score()),
                    () -> "Mate is distance, not evaluation, and drags every weight it touches: "
                            + sample.score());
            assertTrue(sample.result() >= SelfPlaySample.LOSS && sample.result() <= SelfPlaySample.WIN,
                    () -> "Result out of range: " + sample.result());
        }
    }

    @Test
    void everyPositionInOneGameAgreesAboutWhoWon() {
        // One game has one outcome. Seen from the side to move, every white-to-move position must
        // carry the same label, and every black-to-move position the mirror of it. A single
        // disagreement means the perspective flip is wrong somewhere.
        List<SelfPlaySample> samples = new SelfPlayGenerator()
                .play("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", 3);

        Map<Integer, List<Integer>> byMover = samples.stream().collect(Collectors.groupingBy(
                sample -> Board.fromFen(sample.fen()).sideToMove(),
                Collectors.mapping(SelfPlaySample::result, Collectors.toList())));

        for (Map.Entry<Integer, List<Integer>> side : byMover.entrySet()) {
            assertEquals(1, side.getValue().stream().distinct().count(),
                    () -> "Positions with " + (side.getKey() == Pieces.WHITE ? "white" : "black")
                            + " to move disagree about the outcome: " + side.getValue().stream()
                            .distinct().toList());
        }

        List<Integer> whiteLabels = byMover.getOrDefault(Pieces.WHITE, List.of());
        List<Integer> blackLabels = byMover.getOrDefault(Pieces.BLACK, List.of());
        if (!whiteLabels.isEmpty() && !blackLabels.isEmpty()) {
            assertEquals(SelfPlaySample.WIN, whiteLabels.get(0) + blackLabels.get(0),
                    "The two sides must see the same game from opposite ends");
        }
    }
}
