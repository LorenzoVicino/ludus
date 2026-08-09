package io.github.lorenzovicino.ludus.tools.dist;

import io.github.lorenzovicino.ludus.tools.GamePlayer;
import java.io.IOException;
import java.time.Duration;
import java.util.function.Function;

/**
 * The match transport, as a typed layer over {@link RabbitBroker}.
 *
 * <p>All the guarantees — manual acknowledgement, prefetch, publisher confirms, durability,
 * dead-lettering — live in the broker class and are shared with the self-play pipeline. This adds
 * only the queue names and the wire format.
 */
public final class RabbitTransport implements MatchTransport {

    public static final String JOBS_QUEUE = "ludus.match.jobs";
    public static final String RESULTS_QUEUE = "ludus.match.results";
    public static final String JOBS_DEAD_LETTER_QUEUE = "ludus.match.jobs.dlq";

    public static final String DEFAULT_URI = RabbitBroker.DEFAULT_URI;

    private final RabbitBroker broker;

    public RabbitTransport(String uri, int prefetch) throws Exception {
        this.broker = new RabbitBroker(uri, prefetch);
        broker.declareQueue(JOBS_QUEUE, JOBS_DEAD_LETTER_QUEUE);
        broker.declareQueue(RESULTS_QUEUE, null);
    }

    @Override
    public void submitJob(MatchJob job) throws Exception {
        broker.publish(JOBS_QUEUE, job.encode());
    }

    @Override
    public void submitResult(MatchJob job, GamePlayer.PairOutcome outcome) throws Exception {
        broker.publish(RESULTS_QUEUE, MatchJob.encodeResult(job, outcome));
    }

    @Override
    public Delivery<MatchJob> nextJob(Duration timeout) throws Exception {
        return receive(JOBS_QUEUE, timeout, MatchJob::decode);
    }

    @Override
    public Delivery<Completed> nextResult(Duration timeout) throws Exception {
        return receive(RESULTS_QUEUE, timeout, MatchJob::decodeResult);
    }

    private <T> Delivery<T> receive(String queue, Duration timeout, Function<String, T> decode)
            throws Exception {
        RabbitBroker.Raw raw = broker.receive(queue, timeout);
        return raw == null ? null : new BrokerDelivery<>(raw, decode.apply(raw.body()));
    }

    /** Discards jobs nobody will play, once the test has already decided. */
    public int purgeJobs() throws IOException {
        return broker.purge(JOBS_QUEUE);
    }

    public long queuedJobs() throws IOException {
        return broker.depth(JOBS_QUEUE);
    }

    @Override
    public void close() {
        broker.close();
    }

    private record BrokerDelivery<T>(RabbitBroker.Raw raw, T value) implements Delivery<T> {

        @Override
        public void ack() throws IOException {
            raw.ack();
        }

        @Override
        public void requeue() throws IOException {
            raw.requeue();
        }

        @Override
        public void deadLetter() throws IOException {
            raw.deadLetter();
        }
    }
}
