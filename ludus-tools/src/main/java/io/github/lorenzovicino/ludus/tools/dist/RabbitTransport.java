package io.github.lorenzovicino.ludus.tools.dist;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import io.github.lorenzovicino.ludus.tools.GamePlayer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The distributed transport, over RabbitMQ.
 *
 * <h2>Why a broker at all</h2>
 *
 * <p>An SPRT match is 300 to 500 games and the wall time of one is what limits how fast patches can
 * be evaluated. Spreading it across machines needs work handed out, results collected, and — the part
 * a shared file or a socket does badly — a job that comes back when the machine holding it dies
 * halfway through.
 *
 * <h2>What is actually being relied on</h2>
 *
 * <p><strong>Durable queues and persistent messages.</strong> A match represents hours of CPU. A
 * broker restart must not lose the jobs still queued.
 *
 * <p><strong>Manual acknowledgement.</strong> Nothing is acknowledged on receipt. A job is settled
 * only once its games are played and the result is safely published, so a worker that dies mid-job
 * hands it back rather than losing it.
 *
 * <p><strong>Prefetch.</strong> This is the backpressure. A job is minutes of work, so a worker takes
 * one or two at a time rather than being handed the whole queue on connect — otherwise a single
 * worker claims everything and the other machines sit idle.
 *
 * <p><strong>Publisher confirms.</strong> Publishing is fire-and-forget by default. A result dropped
 * on the floor is minutes of CPU thrown away and, worse, a tally that silently disagrees with the
 * games that were actually played. Each publish blocks until the broker has taken responsibility.
 *
 * <p><strong>A dead-letter queue.</strong> A job that fails repeatedly is rejected without requeue,
 * so it stops cycling through workers forever and stays somewhere it can be looked at.
 *
 * <h2>Threading</h2>
 *
 * <p>A channel is not safe for concurrent use, and here three threads want one: the client's own
 * dispatch thread delivering messages, the worker thread acknowledging, and the worker thread
 * publishing. So publishing and consuming get separate channels, and acknowledgements are
 * serialised against the consuming one.
 */
public final class RabbitTransport implements MatchTransport {

    public static final String JOBS_QUEUE = "ludus.match.jobs";
    public static final String RESULTS_QUEUE = "ludus.match.results";
    public static final String JOBS_DEAD_LETTER_QUEUE = "ludus.match.jobs.dlq";

    /**
     * The default virtual host is named {@code /}, and in a URI that slash has to be percent-encoded.
     *
     * <p>Ending the URI with a bare {@code /} looks right and is not: it makes the path segment
     * empty, so the client asks for a virtual host named "" and the broker answers
     * {@code NOT_ALLOWED - vhost  not found} — with the empty name leaving a double space where the
     * name should be, which is the only clue you get.
     */
    public static final String DEFAULT_URI = "amqp://guest:guest@localhost:5672/%2F";

    private static final long CONFIRM_TIMEOUT_MILLIS = 30_000;

    private final Connection connection;
    private final Channel publishChannel;
    private final Channel consumeChannel;
    private final Object publishLock = new Object();
    private final Object consumeLock = new Object();

    private final LinkedBlockingQueue<Raw> incomingJobs = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Raw> incomingResults = new LinkedBlockingQueue<>();
    private boolean consumingJobs;
    private boolean consumingResults;

    public RabbitTransport(String uri, int prefetch) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(uri);
        // A worker on another machine should survive the network hiccup that a long match will
        // eventually hit, rather than dying and losing its place.
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5_000);

        this.connection = factory.newConnection("ludus-match");
        this.publishChannel = connection.createChannel();
        this.consumeChannel = connection.createChannel();

        declareTopology(publishChannel);
        publishChannel.confirmSelect();
        consumeChannel.basicQos(Math.max(1, prefetch));
    }

    private static void declareTopology(Channel channel) throws IOException {
        channel.queueDeclare(JOBS_DEAD_LETTER_QUEUE, true, false, false, null);

        Map<String, Object> jobArguments = new HashMap<>();
        // The default exchange routes by queue name, so a rejected job lands straight in the DLQ.
        jobArguments.put("x-dead-letter-exchange", "");
        jobArguments.put("x-dead-letter-routing-key", JOBS_DEAD_LETTER_QUEUE);

        channel.queueDeclare(JOBS_QUEUE, true, false, false, jobArguments);
        channel.queueDeclare(RESULTS_QUEUE, true, false, false, null);
    }

    @Override
    public void submitJob(MatchJob job) throws Exception {
        publish(JOBS_QUEUE, job.encode());
    }

    @Override
    public void submitResult(MatchJob job, GamePlayer.PairOutcome outcome) throws Exception {
        publish(RESULTS_QUEUE, MatchJob.encodeResult(job, outcome));
    }

    private void publish(String queue, String body) throws Exception {
        synchronized (publishLock) {
            publishChannel.basicPublish("", queue, MessageProperties.PERSISTENT_TEXT_PLAIN,
                    body.getBytes(StandardCharsets.UTF_8));
            publishChannel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MILLIS);
        }
    }

    @Override
    public Delivery<MatchJob> nextJob(Duration timeout) throws Exception {
        startJobConsumer();
        Raw raw = incomingJobs.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return raw == null ? null : new AmqpDelivery<>(raw.tag(), MatchJob.decode(raw.body()));
    }

    @Override
    public Delivery<Completed> nextResult(Duration timeout) throws Exception {
        startResultConsumer();
        Raw raw = incomingResults.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return raw == null ? null : new AmqpDelivery<>(raw.tag(), MatchJob.decodeResult(raw.body()));
    }

    private void startJobConsumer() throws IOException {
        synchronized (consumeLock) {
            if (consumingJobs) {
                return;
            }
            consumeChannel.basicConsume(JOBS_QUEUE, false,
                    (tag, delivery) -> incomingJobs.add(toRaw(delivery)),
                    tag -> { });
            consumingJobs = true;
        }
    }

    private void startResultConsumer() throws IOException {
        synchronized (consumeLock) {
            if (consumingResults) {
                return;
            }
            consumeChannel.basicConsume(RESULTS_QUEUE, false,
                    (tag, delivery) -> incomingResults.add(toRaw(delivery)),
                    tag -> { });
            consumingResults = true;
        }
    }

    private static Raw toRaw(com.rabbitmq.client.Delivery delivery) {
        return new Raw(delivery.getEnvelope().getDeliveryTag(),
                new String(delivery.getBody(), StandardCharsets.UTF_8));
    }

    /** Discards jobs nobody will play, once the test has already decided. */
    public int purgeJobs() throws IOException {
        synchronized (publishLock) {
            return publishChannel.queuePurge(JOBS_QUEUE).getMessageCount();
        }
    }

    public long queuedJobs() throws IOException {
        synchronized (publishLock) {
            return publishChannel.messageCount(JOBS_QUEUE);
        }
    }

    @Override
    public void close() {
        try {
            connection.close(5_000);
        } catch (IOException e) {
            // Shutting down; the broker will reap the connection either way.
        }
    }

    private record Raw(long tag, String body) {
    }

    private final class AmqpDelivery<T> implements Delivery<T> {

        private final long tag;
        private final T value;

        private AmqpDelivery(long tag, T value) {
            this.tag = tag;
            this.value = value;
        }

        @Override
        public T value() {
            return value;
        }

        @Override
        public void ack() throws IOException {
            synchronized (consumeLock) {
                consumeChannel.basicAck(tag, false);
            }
        }

        @Override
        public void requeue() throws IOException {
            synchronized (consumeLock) {
                consumeChannel.basicNack(tag, false, true);
            }
        }

        @Override
        public void deadLetter() throws IOException {
            synchronized (consumeLock) {
                // No requeue: the dead-letter routing on the queue takes it from here.
                consumeChannel.basicNack(tag, false, false);
            }
        }
    }
}
