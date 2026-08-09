package io.github.lorenzovicino.ludus.tools;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs a match on this machine, with a thread per pair of engine processes.
 *
 * <p>The game rules live in {@link GamePlayer}; this class only decides who plays what and when to
 * stop. The distributed runner in {@code tools.dist} makes the same decisions with a broker in the
 * middle, and plays by exactly the same rules because it uses the same {@code GamePlayer}.
 */
public final class MatchRunner {

    /**
     * @param moveTimeMillis a fixed allowance per move. Fixed time rather than a running clock,
     *                       deliberately: it isolates what the search does with the time it gets from
     *                       how well each version divides a clock, which are separate questions and
     *                       would otherwise be measured together.
     * @param maxPlies       cap for a game neither side can finish
     * @param stopOnVerdict  end the match as soon as a bound is crossed. True is what you want when
     *                       gating a patch: the test has answered and further games cost time for
     *                       nothing. False plays every opening, which is what you want when the
     *                       headline number matters — a test that stops at eleven games has decided
     *                       the question but pinned the Elo only to within a few hundred points
     */
    public record Config(int openingPairs, long moveTimeMillis, int maxPlies, int concurrency,
                         boolean stopOnVerdict, Duration replyTimeout) {
    }

    private final List<String> commandA;
    private final List<String> commandB;
    private final List<String> openings;
    private final Config config;
    private final Sprt sprt;

    private final MatchTally tally;
    private final AtomicBoolean stop = new AtomicBoolean();

    public MatchRunner(List<String> commandA, List<String> commandB, List<String> openings,
                       Config config, Sprt sprt) {
        this.commandA = commandA;
        this.commandB = commandB;
        this.openings = openings;
        this.config = config;
        this.sprt = sprt;
        this.tally = new MatchTally(sprt, config.stopOnVerdict(), stop);
    }

    /** @return the result from A's point of view */
    public MatchResult run() throws InterruptedException {
        Queue<String> pending = new ConcurrentLinkedQueue<>(
                openings.subList(0, Math.min(config.openingPairs(), openings.size())));

        int workers = Math.max(1, config.concurrency());
        CountDownLatch done = new CountDownLatch(workers);

        for (int i = 0; i < workers; i++) {
            Thread worker = new Thread(() -> {
                try {
                    workLoop(pending);
                } catch (RuntimeException e) {
                    System.err.println("worker stopped: " + e.getMessage());
                } finally {
                    done.countDown();
                }
            }, "match-worker-" + i);
            worker.setDaemon(true);
            worker.start();
        }
        done.await();

        return tally.result();
    }

    private void workLoop(Queue<String> pending) {
        // Each worker owns its own pair of processes: engines are stateful and single-threaded, and
        // sharing one across concurrent games would interleave two searches in one engine.
        try (GamePlayer player = new GamePlayer(commandA, commandB, config.moveTimeMillis(),
                config.maxPlies(), config.replyTimeout())) {

            String opening;
            while (!stop.get() && (opening = pending.poll()) != null) {
                tally.record(player.playPair(opening));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not start an engine: " + e.getMessage(), e);
        }
    }
}
