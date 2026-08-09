package io.github.lorenzovicino.ludus.tools.selfplay;

import io.github.lorenzovicino.ludus.tools.EndgameSeeds;
import io.github.lorenzovicino.ludus.tools.OpeningBook;
import io.github.lorenzovicino.ludus.tools.dist.RabbitBroker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Produces training data. Runs on any machine with cores to spare.
 *
 * <pre>
 * java -jar ludus-match.jar generate --concurrency 6
 * </pre>
 *
 * <p>Takes jobs off a queue, plays the games, and publishes the positions worth keeping. The writer
 * consumes them as they arrive, so generation and training can run at the same time on different
 * machines — which for a project with a fixed amount of CPU is the practical arrangement, not a
 * showpiece.
 */
public final class GeneratorMain {

    /**
     * Samples per message. One position per message would put the broker under a load the search
     * never approaches; a few thousand lines is a message of a couple of hundred kilobytes, which is
     * comfortable and still small enough that losing one costs seconds rather than minutes.
     */
    private static final int BATCH_SIZE = 2_000;

    private static final Duration POLL = Duration.ofSeconds(5);

    private GeneratorMain() {
    }

    public static int run(String[] args) throws Exception {
        String uri = RabbitBroker.DEFAULT_URI;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        int idleSeconds = 120;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--broker" -> uri = value(args, ++i, "--broker");
                case "--concurrency" -> threads = Integer.parseInt(value(args, ++i, "--concurrency"));
                case "--idle-timeout" -> idleSeconds = Integer.parseInt(value(args, ++i, "--idle-timeout"));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        try (RabbitBroker broker = new RabbitBroker(uri, threads)) {
            broker.declareQueue(SelfPlayJob.JOBS_QUEUE, SelfPlayJob.JOBS_DEAD_LETTER_QUEUE);
            broker.declareQueue(SelfPlayJob.SAMPLES_QUEUE, null);

            System.out.printf("generator connected to %s, %d thread(s)%n", uri, threads);

            Duration idle = Duration.ofSeconds(idleSeconds);
            Thread[] workers = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                workers[i] = new Thread(() -> generate(broker, idle), "selfplay-" + i);
                workers[i].start();
            }
            for (Thread worker : workers) {
                worker.join();
            }
        }
        System.out.println("generator idle, exiting");
        return 0;
    }

    private static void generate(RabbitBroker broker, Duration idleTimeout) {
        SelfPlayGenerator generator = new SelfPlayGenerator();
        long idleDeadline = System.nanoTime() + idleTimeout.toNanos();

        try {
            while (true) {
                RabbitBroker.Raw raw = broker.receive(SelfPlayJob.JOBS_QUEUE, POLL);
                if (raw == null) {
                    if (System.nanoTime() > idleDeadline) {
                        return;
                    }
                    continue;
                }
                idleDeadline = System.nanoTime() + idleTimeout.toNanos();

                SelfPlayJob job;
                try {
                    job = SelfPlayJob.decode(raw.body());
                } catch (RuntimeException e) {
                    // Retrying will not fix a message we cannot parse.
                    System.err.println("unparseable job, dead-lettering: " + raw.body());
                    raw.deadLetter();
                    continue;
                }

                try {
                    int produced = playAndPublish(broker, generator, job);
                    // Acknowledged only once every sample is published. A crash before this point
                    // hands the whole job back, which may duplicate samples on the retry — harmless
                    // for training data, and the reason a match tally settles differently.
                    raw.ack();
                    System.out.printf("job %d (%s): %d games, %d samples%n", job.id(),
                            job.fromEndgame() ? "endgame" : "opening", job.games(), produced);
                } catch (RuntimeException e) {
                    System.err.printf("job %d failed (%s); handing it back%n", job.id(), e);
                    raw.requeue();
                }
            }
        } catch (Exception e) {
            System.err.println("generator thread stopped: " + e);
        }
    }

    private static int playAndPublish(RabbitBroker broker, SelfPlayGenerator generator,
                                      SelfPlayJob job) {
        // Endgames are constructed rather than played into: self-play between equal engines ends in
        // the middlegame or by the fifty-move rule, so a dataset built only from openings is thin
        // exactly where the evaluation behaves least like its middlegame self.
        List<String> openings = job.fromEndgame()
                ? EndgameSeeds.generate(job.games(), job.seed())
                : OpeningBook.generate(job.games(), 8, job.seed());
        List<String> batch = new ArrayList<>(BATCH_SIZE);
        int produced = 0;

        try {
            for (String opening : openings) {
                for (SelfPlaySample sample : generator.play(opening, job.depth())) {
                    batch.add(sample.encode());
                    produced++;
                    if (batch.size() >= BATCH_SIZE) {
                        publish(broker, batch);
                        batch.clear();
                    }
                }
            }
            if (!batch.isEmpty()) {
                publish(broker, batch);
            }
        } catch (Exception e) {
            throw new IllegalStateException("publishing samples failed", e);
        }
        return produced;
    }

    private static void publish(RabbitBroker broker, List<String> batch) throws Exception {
        // The body is exactly the text the writer appends to the dataset, so no reformatting happens
        // on the way out and nothing can be lost in translation between the two ends.
        broker.publish(SelfPlayJob.SAMPLES_QUEUE, String.join("\n", batch));
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
