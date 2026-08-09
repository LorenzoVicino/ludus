package io.github.lorenzovicino.ludus.tools.dist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.tools.GamePlayer;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The wire format and the delivery semantics the distributed runner depends on.
 *
 * <p>Tested through {@link InMemoryTransport} rather than a broker so they run in CI. What is being
 * checked is not RabbitMQ — it is that the coordination logic behaves correctly when a message is
 * handed back, which is the case that actually costs something when it is wrong.
 */
class MatchTransportTest {

    private static final Duration INSTANT = Duration.ofMillis(50);
    private static final String KIWIPETE =
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";

    @Test
    void aJobSurvivesTheWireFormat() {
        // A FEN carries spaces and slashes; the delimiter has to survive both.
        MatchJob job = new MatchJob(42, KIWIPETE);
        MatchJob restored = MatchJob.decode(job.encode());

        assertEquals(job, restored);
        assertEquals(KIWIPETE, restored.fen());
        assertTrue(Board.fromFen(restored.fen()).toFen().startsWith("r3k2r"),
                "The decoded FEN has to still parse as a position");
    }

    @Test
    void aResultCarriesEveryCounter() {
        MatchJob job = new MatchJob(7, KIWIPETE);
        GamePlayer.PairOutcome outcome = new GamePlayer.PairOutcome(1, 1, 0, 0, 1);

        MatchTransport.Completed restored =
                MatchJob.decodeResult(MatchJob.encodeResult(job, outcome));

        assertEquals(job, restored.job());
        assertEquals(outcome, restored.outcome());
        assertEquals(1, restored.outcome().illegalByB(),
                "An illegal move must survive the trip, or it hides inside the score");
    }

    @Test
    void malformedMessagesAreRejectedRatherThanGuessedAt() {
        assertThrows(IllegalArgumentException.class, () -> MatchJob.decode("no separator here"));
        assertThrows(IllegalArgumentException.class, () -> MatchJob.decodeResult("1|2|3"));
    }

    @Test
    void anAcknowledgedJobDoesNotComeBack() throws Exception {
        try (InMemoryTransport transport = new InMemoryTransport()) {
            transport.submitJob(new MatchJob(1, KIWIPETE));

            MatchTransport.Delivery<MatchJob> first = transport.nextJob(INSTANT);
            assertNotNull(first);
            first.ack();

            assertNull(transport.nextJob(INSTANT), "Acknowledged work must be gone");
        }
    }

    @Test
    void aJobHandedBackIsGivenToSomebodyElse() throws Exception {
        // The scenario the whole design exists for: a worker takes a job worth minutes of CPU and
        // then dies. The job has to come back, not vanish with it.
        try (InMemoryTransport transport = new InMemoryTransport()) {
            transport.submitJob(new MatchJob(1, KIWIPETE));

            MatchTransport.Delivery<MatchJob> taken = transport.nextJob(INSTANT);
            assertNotNull(taken);
            taken.requeue();

            MatchTransport.Delivery<MatchJob> retaken = transport.nextJob(INSTANT);
            assertNotNull(retaken, "A requeued job must be delivered again");
            assertEquals(1, retaken.value().id());
        }
    }

    @Test
    void aPoisonJobStopsCyclingAndIsKeptForInspection() throws Exception {
        try (InMemoryTransport transport = new InMemoryTransport()) {
            transport.submitJob(new MatchJob(99, KIWIPETE));

            MatchTransport.Delivery<MatchJob> taken = transport.nextJob(INSTANT);
            assertNotNull(taken);
            taken.deadLetter();

            assertNull(transport.nextJob(INSTANT), "A dead-lettered job must not be redelivered");
            assertEquals(1, transport.deadLettered().size());
            assertEquals(99, transport.deadLettered().get(0).id());
        }
    }

    @Test
    void resultsTravelBackWithTheirJob() throws Exception {
        try (InMemoryTransport transport = new InMemoryTransport()) {
            MatchJob job = new MatchJob(3, KIWIPETE);
            transport.submitResult(job, new GamePlayer.PairOutcome(2, 0, 0, 0, 0));

            MatchTransport.Delivery<MatchTransport.Completed> delivery = transport.nextResult(INSTANT);
            assertNotNull(delivery);
            assertEquals(3, delivery.value().job().id());
            assertEquals(2, delivery.value().outcome().wins());
            delivery.ack();
        }
    }

    @Test
    void anEmptyQueueTimesOutInsteadOfBlockingForever() throws Exception {
        try (InMemoryTransport transport = new InMemoryTransport()) {
            long start = System.nanoTime();
            assertNull(transport.nextJob(INSTANT));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMillis < 2_000,
                    () -> "A coordinator with no workers must notice, not hang: " + elapsedMillis + " ms");
        }
    }

    @Test
    void outcomesAddUp() {
        GamePlayer.PairOutcome sum = new GamePlayer.PairOutcome(1, 0, 0, 0, 0)
                .plus(new GamePlayer.PairOutcome(0, 1, 0, 0, 0))
                .plus(new GamePlayer.PairOutcome(0, 0, 1, 1, 0));

        assertEquals(new GamePlayer.PairOutcome(1, 1, 1, 1, 0), sum);
        assertEquals(3, sum.games());
    }
}
