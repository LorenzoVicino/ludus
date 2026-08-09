package io.github.lorenzovicino.ludus.tools.dist;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A connection to RabbitMQ and the handful of guarantees this project actually relies on.
 *
 * <p>Untyped on purpose: two pipelines run over this — distributing an SPRT match, and generating
 * self-play training data — and they have the same shape, a queue of work and a queue of results.
 * Two copies of connection handling, publisher confirms and acknowledgement would be two places for
 * the guarantees to quietly diverge.
 *
 * <h2>What is being relied on</h2>
 *
 * <p><strong>Manual acknowledgement.</strong> Nothing is settled on receipt. Work is acknowledged
 * only once it is finished and its output is safely published, so a machine that dies mid-job hands
 * the work back rather than losing it.
 *
 * <p><strong>Prefetch.</strong> The backpressure. A unit of work here is minutes, so a consumer takes
 * as many as it can actually run and no more — otherwise the first machine to connect claims
 * everything and the rest idle.
 *
 * <p><strong>Publisher confirms.</strong> Publishing is fire-and-forget by default. Losing a result
 * costs minutes of CPU and leaves a tally that silently disagrees with the work that was done, so
 * each publish blocks until the broker has taken responsibility.
 *
 * <p><strong>Durable queues, persistent messages, and a dead-letter queue.</strong> A broker restart
 * must not discard work in progress, and a message that cannot be processed has to stop cycling.
 *
 * <h2>Threading</h2>
 *
 * <p>A channel is not safe for concurrent use, and three threads want one here: the client's dispatch
 * thread delivering messages, a worker acknowledging, and a worker publishing. Publishing and
 * consuming therefore get separate channels, and acknowledgements are serialised against the
 * consuming one.
 */
public final class RabbitBroker implements AutoCloseable {

    /**
     * The default virtual host is named {@code /}, and in a URI that slash has to be
     * percent-encoded.
     *
     * <p>Ending the URI with a bare {@code /} looks right and is not: it makes the path segment
     * empty, so the client asks for a virtual host named "" and the broker answers
     * {@code NOT_ALLOWED - vhost  not found} — the empty name leaving a double space where the name
     * should be, which is the only clue you get.
     */
    public static final String DEFAULT_URI = "amqp://guest:guest@localhost:5672/%2F";

    private static final long CONFIRM_TIMEOUT_MILLIS = 30_000;

    private final Connection connection;
    private final Channel publishChannel;
    private final Channel consumeChannel;
    private final Object publishLock = new Object();
    private final Object consumeLock = new Object();

    private final Map<String, LinkedBlockingQueue<Raw>> inboxes = new ConcurrentHashMap<>();

    public RabbitBroker(String uri, int prefetch) throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setUri(uri);
        // A worker on another machine should survive the network hiccup a long run will eventually
        // hit, rather than dying and losing its place.
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5_000);

        this.connection = factory.newConnection("ludus");
        this.publishChannel = connection.createChannel();
        this.consumeChannel = connection.createChannel();

        publishChannel.confirmSelect();
        consumeChannel.basicQos(Math.max(1, prefetch));
    }

    /**
     * Declares a durable queue and, when {@code deadLetterQueue} is given, the queue its rejects go
     * to.
     */
    public void declareQueue(String name, String deadLetterQueue) throws IOException {
        Map<String, Object> arguments = new HashMap<>();
        if (deadLetterQueue != null) {
            publishChannel.queueDeclare(deadLetterQueue, true, false, false, null);
            // The default exchange routes by queue name, so a rejected message lands straight there.
            arguments.put("x-dead-letter-exchange", "");
            arguments.put("x-dead-letter-routing-key", deadLetterQueue);
        }
        publishChannel.queueDeclare(name, true, false, false, arguments);
    }

    public void publish(String queue, String body) throws Exception {
        synchronized (publishLock) {
            publishChannel.basicPublish("", queue, MessageProperties.PERSISTENT_TEXT_PLAIN,
                    body.getBytes(StandardCharsets.UTF_8));
            publishChannel.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MILLIS);
        }
    }

    /** @return the next message on {@code queue}, or {@code null} if none arrived in time. */
    public Raw receive(String queue, Duration timeout) throws Exception {
        LinkedBlockingQueue<Raw> inbox = startConsumer(queue);
        return inbox.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private LinkedBlockingQueue<Raw> startConsumer(String queue) throws IOException {
        LinkedBlockingQueue<Raw> existing = inboxes.get(queue);
        if (existing != null) {
            return existing;
        }
        synchronized (consumeLock) {
            LinkedBlockingQueue<Raw> inbox = inboxes.get(queue);
            if (inbox != null) {
                return inbox;
            }
            LinkedBlockingQueue<Raw> created = new LinkedBlockingQueue<>();
            consumeChannel.basicConsume(queue, false,
                    (tag, delivery) -> created.add(new Raw(this,
                            delivery.getEnvelope().getDeliveryTag(),
                            new String(delivery.getBody(), StandardCharsets.UTF_8))),
                    tag -> { });
            inboxes.put(queue, created);
            return created;
        }
    }

    /** Discards everything queued. Used when work in flight has become irrelevant. */
    public int purge(String queue) throws IOException {
        synchronized (publishLock) {
            return publishChannel.queuePurge(queue).getMessageCount();
        }
    }

    public long depth(String queue) throws IOException {
        synchronized (publishLock) {
            return publishChannel.messageCount(queue);
        }
    }

    void settle(long tag, Settlement settlement) throws IOException {
        synchronized (consumeLock) {
            switch (settlement) {
                case ACK -> consumeChannel.basicAck(tag, false);
                case REQUEUE -> consumeChannel.basicNack(tag, false, true);
                // No requeue: the dead-letter routing on the queue takes it from here.
                case DEAD_LETTER -> consumeChannel.basicNack(tag, false, false);
            }
        }
    }

    @Override
    public void close() {
        try {
            connection.close(5_000);
        } catch (IOException e) {
            // Shutting down; the broker reaps the connection either way.
        }
    }

    enum Settlement {
        ACK, REQUEUE, DEAD_LETTER
    }

    /** A received message, not yet settled. */
    public static final class Raw {

        private final RabbitBroker broker;
        private final long tag;
        private final String body;

        private Raw(RabbitBroker broker, long tag, String body) {
            this.broker = Objects.requireNonNull(broker);
            this.tag = tag;
            this.body = body;
        }

        public String body() {
            return body;
        }

        public void ack() throws IOException {
            broker.settle(tag, Settlement.ACK);
        }

        public void requeue() throws IOException {
            broker.settle(tag, Settlement.REQUEUE);
        }

        public void deadLetter() throws IOException {
            broker.settle(tag, Settlement.DEAD_LETTER);
        }
    }
}
