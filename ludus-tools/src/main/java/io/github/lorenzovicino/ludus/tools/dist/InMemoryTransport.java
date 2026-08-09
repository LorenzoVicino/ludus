package io.github.lorenzovicino.ludus.tools.dist;

import io.github.lorenzovicino.ludus.tools.GamePlayer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A transport with no broker, for testing the coordination logic.
 *
 * <p>It reproduces the part that the logic actually depends on: a message is not gone until it is
 * acknowledged, a requeued message comes back, and a dead-lettered one does not. That is enough to
 * test that a coordinator finishes a match when a worker dies mid-job — the scenario worth testing
 * and the one hardest to arrange against a real broker.
 */
public final class InMemoryTransport implements MatchTransport {

    private final LinkedBlockingQueue<MatchJob> jobs = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<Completed> results = new LinkedBlockingQueue<>();
    private final List<MatchJob> deadLettered = new CopyOnWriteArrayList<>();

    @Override
    public void submitJob(MatchJob job) {
        jobs.add(job);
    }

    @Override
    public void submitResult(MatchJob job, GamePlayer.PairOutcome outcome) {
        results.add(new Completed(job, outcome));
    }

    @Override
    public Delivery<MatchJob> nextJob(Duration timeout) throws InterruptedException {
        MatchJob job = jobs.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return job == null ? null : new InMemoryDelivery<>(job, () -> jobs.add(job),
                () -> deadLettered.add(job));
    }

    @Override
    public Delivery<Completed> nextResult(Duration timeout) throws InterruptedException {
        Completed completed = results.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        return completed == null ? null
                : new InMemoryDelivery<>(completed, () -> results.add(completed), () -> { });
    }

    /** Jobs abandoned as unplayable. Empty is the expected state. */
    public List<MatchJob> deadLettered() {
        return List.copyOf(deadLettered);
    }

    public int pendingJobs() {
        return jobs.size();
    }

    @Override
    public void close() {
        jobs.clear();
        results.clear();
    }

    private record InMemoryDelivery<T>(T value, Runnable onRequeue, Runnable onDeadLetter)
            implements Delivery<T> {

        @Override
        public void ack() {
            // Already removed from the queue when it was polled.
        }

        @Override
        public void requeue() {
            onRequeue.run();
        }

        @Override
        public void deadLetter() {
            onDeadLetter.run();
        }
    }
}
