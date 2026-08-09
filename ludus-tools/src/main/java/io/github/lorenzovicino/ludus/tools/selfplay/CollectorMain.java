package io.github.lorenzovicino.ludus.tools.selfplay;

import io.github.lorenzovicino.ludus.tools.dist.RabbitBroker;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;

/**
 * Schedules the work and writes the dataset as it arrives.
 *
 * <pre>
 * java -jar ludus-match.jar collect --samples 2000000 --out training/data/selfplay.txt
 * </pre>
 *
 * <p>Publishes the jobs, then consumes sample batches until it has enough or the generators go quiet.
 * Writing as the data arrives rather than at the end is the point of putting a queue here: generation
 * can run on several machines for hours, and the dataset grows the whole time instead of appearing
 * once everything finishes.
 *
 * <p>The batch body is appended verbatim. Nothing is reparsed on the way in, so there is no format to
 * disagree about between the two ends — and a batch that is somehow malformed is visible in the file
 * rather than silently reshaped.
 */
public final class CollectorMain {

    private static final Duration POLL = Duration.ofSeconds(10);

    private CollectorMain() {
    }

    public static int run(String[] args) throws Exception {
        String uri = RabbitBroker.DEFAULT_URI;
        Path out = Path.of("training/data/selfplay.txt");
        int targetSamples = 100_000;
        int gamesPerJob = 50;
        int depth = SelfPlayGenerator.DEFAULT_DEPTH;
        long seed = 20260808L;
        int idleMinutes = 10;
        boolean append = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--broker" -> uri = value(args, ++i, "--broker");
                case "--out" -> out = Path.of(value(args, ++i, "--out"));
                case "--samples" -> targetSamples = Integer.parseInt(value(args, ++i, "--samples"));
                case "--games-per-job" -> gamesPerJob = Integer.parseInt(value(args, ++i, "--games-per-job"));
                case "--depth" -> depth = Integer.parseInt(value(args, ++i, "--depth"));
                case "--seed" -> seed = Long.parseLong(value(args, ++i, "--seed"));
                case "--idle-timeout" -> idleMinutes = Integer.parseInt(value(args, ++i, "--idle-timeout"));
                case "--append" -> append = true;
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // A rough guess at samples per game, only used to decide how many jobs to queue. Over-queuing
        // is harmless: the leftovers are purged once the target is reached.
        int estimatedPerGame = 20;
        int jobs = Math.max(1, targetSamples / Math.max(1, gamesPerJob * estimatedPerGame)) + 1;

        long written = 0;
        long batches = 0;

        try (RabbitBroker broker = new RabbitBroker(uri, 4);
             BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE,
                     append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {

            broker.declareQueue(SelfPlayJob.JOBS_QUEUE, SelfPlayJob.JOBS_DEAD_LETTER_QUEUE);
            broker.declareQueue(SelfPlayJob.SAMPLES_QUEUE, null);

            int stale = broker.purge(SelfPlayJob.JOBS_QUEUE);
            if (stale > 0) {
                System.out.printf("purged %d stale job(s)%n", stale);
            }

            System.out.printf("queueing %d jobs of %d games at depth %d, target %,d samples%n",
                    jobs, gamesPerJob, depth, targetSamples);
            for (int i = 0; i < jobs; i++) {
                broker.publish(SelfPlayJob.JOBS_QUEUE,
                        new SelfPlayJob(i, seed + i, gamesPerJob, depth).encode());
            }
            System.out.println("writing to " + out.toAbsolutePath() + ", waiting for generators");

            long deadline = System.nanoTime() + Duration.ofMinutes(idleMinutes).toNanos();

            while (written < targetSamples) {
                RabbitBroker.Raw raw = broker.receive(SelfPlayJob.SAMPLES_QUEUE, POLL);
                if (raw == null) {
                    if (System.nanoTime() > deadline) {
                        System.err.printf("no samples for %d minutes; is a generator running?%n",
                                idleMinutes);
                        break;
                    }
                    continue;
                }
                deadline = System.nanoTime() + Duration.ofMinutes(idleMinutes).toNanos();

                String body = raw.body();
                writer.write(body);
                writer.write('\n');
                // Flushed before acknowledging: the other order would let a crash in between discard
                // the batch while its samples never reached the disk.
                writer.flush();
                raw.ack();

                batches++;
                written += body.lines().count();
                if (batches % 10 == 0) {
                    System.out.printf("%,d samples in %d batches%n", written, batches);
                }
            }

            int abandoned = broker.purge(SelfPlayJob.JOBS_QUEUE);
            if (abandoned > 0) {
                System.out.printf("target reached; abandoned %d unstarted job(s)%n", abandoned);
            }
        }

        System.out.println();
        System.out.printf(Locale.ROOT, "wrote %,d samples in %d batches to %s%n",
                written, batches, out.toAbsolutePath());
        return written > 0 ? 0 : 2;
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
