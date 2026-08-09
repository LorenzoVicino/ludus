package io.github.lorenzovicino.ludus.tools.dist;

import io.github.lorenzovicino.ludus.tools.GamePlayer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * A machine that plays games somebody else scheduled.
 *
 * <pre>
 * java -jar ludus-match.jar worker \
 *     --engine-a "java -jar build/candidate.jar" \
 *     --engine-b "java -jar build/baseline.jar" \
 *     --movetime 100 --concurrency 4
 * </pre>
 *
 * <p>Both engine jars must exist on this machine at these paths — the broker carries openings and
 * results, not binaries. Start as many workers on as many machines as there are cores to spare; the
 * coordinator does not need to know they exist.
 */
public final class WorkerMain {

    /** Long enough to outlast the gap between a coordinator finishing one match and starting another. */
    private static final Duration POLL = Duration.ofSeconds(5);

    private WorkerMain() {
    }

    public static int run(String[] args) throws Exception {
        String engineA = null;
        String engineB = null;
        String uri = RabbitTransport.DEFAULT_URI;
        long moveTime = 100;
        int maxPlies = 300;
        int threads = 1;
        int idleSeconds = 120;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--engine-a" -> engineA = value(args, ++i, "--engine-a");
                case "--engine-b" -> engineB = value(args, ++i, "--engine-b");
                case "--broker" -> uri = value(args, ++i, "--broker");
                case "--movetime" -> moveTime = Long.parseLong(value(args, ++i, "--movetime"));
                case "--max-plies" -> maxPlies = Integer.parseInt(value(args, ++i, "--max-plies"));
                case "--concurrency" -> threads = Integer.parseInt(value(args, ++i, "--concurrency"));
                case "--idle-timeout" -> idleSeconds = Integer.parseInt(value(args, ++i, "--idle-timeout"));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (engineA == null || engineB == null) {
            throw new IllegalArgumentException("both --engine-a and --engine-b are required");
        }

        // Prefetch matches the number of threads and no more. A job is minutes of work, so a worker
        // that grabbed the whole queue on connect would leave every other machine idle while it
        // chewed through the match alone.
        try (RabbitTransport transport = new RabbitTransport(uri, threads)) {
            System.out.printf("worker connected to %s, %d thread(s), %d ms per move%n",
                    uri, threads, moveTime);

            List<String> commandA = split(engineA);
            List<String> commandB = split(engineB);
            long moveTimeMillis = moveTime;
            int plyCap = maxPlies;
            Duration idle = Duration.ofSeconds(idleSeconds);

            Thread[] workers = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                workers[i] = new Thread(
                        () -> serve(transport, commandA, commandB, moveTimeMillis, plyCap, idle),
                        "match-worker-" + i);
                workers[i].start();
            }
            for (Thread worker : workers) {
                worker.join();
            }
        }
        System.out.println("worker idle, exiting");
        return 0;
    }

    private static void serve(MatchTransport transport, List<String> commandA, List<String> commandB,
                              long moveTimeMillis, int maxPlies, Duration idleTimeout) {
        // One pair of engine processes per thread: engines are single-threaded and stateful, so two
        // concurrent games through one process would interleave two searches.
        try (GamePlayer player = new GamePlayer(commandA, commandB, moveTimeMillis, maxPlies,
                Duration.ofSeconds(30))) {

            long idleDeadline = System.nanoTime() + idleTimeout.toNanos();

            while (true) {
                MatchTransport.Delivery<MatchJob> delivery = transport.nextJob(POLL);
                if (delivery == null) {
                    if (System.nanoTime() > idleDeadline) {
                        return;
                    }
                    continue;
                }
                idleDeadline = System.nanoTime() + idleTimeout.toNanos();

                MatchJob job = delivery.value();
                try {
                    GamePlayer.PairOutcome outcome = player.playPair(job.fen());
                    // Publish before acknowledging. The other order would let a crash in between
                    // discard the job while its result never arrived, and the coordinator would wait
                    // for a game nobody is playing.
                    transport.submitResult(job, outcome);
                    delivery.ack();
                    System.out.printf("job %d done: %d-%d-%d%n", job.id(),
                            outcome.wins(), outcome.draws(), outcome.losses());
                } catch (RuntimeException e) {
                    System.err.printf("job %d failed (%s); handing it back%n", job.id(), e);
                    delivery.requeue();
                }
            }
        } catch (Exception e) {
            System.err.println("worker thread stopped: " + e);
        }
    }

    private static List<String> split(String command) {
        return Arrays.asList(command.trim().split("\\s+"));
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
