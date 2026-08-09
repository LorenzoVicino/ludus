package io.github.lorenzovicino.ludus.tools.dist;

import io.github.lorenzovicino.ludus.tools.GamePlayer;
import java.time.Duration;

/**
 * Moves match work between a coordinator and its workers.
 *
 * <p>An interface rather than a direct dependency on the broker, for the same reason the engine has
 * one in front of its evaluation: the coordination logic is worth testing on its own, and a test that
 * needs a running broker is a test that does not run in CI. {@link InMemoryTransport} covers the
 * logic; {@link RabbitTransport} is what a real distributed match uses.
 *
 * <h2>Why deliveries are settled explicitly</h2>
 *
 * <p>A job is one opening played twice, which is minutes of work. If the worker holding it dies —
 * killed, unplugged, out of memory — that job must come back and be given to somebody else, not
 * vanish. So nothing is acknowledged on receipt: {@link Delivery#ack()} happens only after the games
 * are actually finished and their result is safely published.
 *
 * <p>That is also what stops a poison opening from cycling forever. A job that keeps failing is
 * dead-lettered instead of requeued, so it stops consuming workers and stays available for
 * inspection.
 */
public interface MatchTransport extends AutoCloseable {

    /** Coordinator side: offer an opening to whichever worker takes it next. */
    void submitJob(MatchJob job) throws Exception;

    /** Worker side: report a finished job. */
    void submitResult(MatchJob job, GamePlayer.PairOutcome outcome) throws Exception;

    /** Worker side: the next opening to play, or {@code null} if none arrived in time. */
    Delivery<MatchJob> nextJob(Duration timeout) throws Exception;

    /** Coordinator side: the next finished result, or {@code null} if none arrived in time. */
    Delivery<Completed> nextResult(Duration timeout) throws Exception;

    @Override
    void close();

    /**
     * A message that has been received but not yet settled. Exactly one of the three settle methods
     * must be called, and the work must be finished before {@link #ack()} is.
     */
    interface Delivery<T> {

        T value();

        /** Done. The message will not be delivered again. */
        void ack() throws Exception;

        /** Not done, and worth retrying. Another worker may pick it up. */
        void requeue() throws Exception;

        /** Not done, and retrying will not help. Sent to the dead-letter queue for inspection. */
        void deadLetter() throws Exception;
    }

    /** A job and what came of it. */
    record Completed(MatchJob job, GamePlayer.PairOutcome outcome) {
    }
}
