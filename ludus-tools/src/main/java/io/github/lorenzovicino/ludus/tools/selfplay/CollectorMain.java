package io.github.lorenzovicino.ludus.tools.selfplay;

import io.github.lorenzovicino.ludus.tools.EndgameSeeds;
import io.github.lorenzovicino.ludus.tools.OpeningBook;
import io.github.lorenzovicino.ludus.tools.dist.RabbitBroker;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
        // Endgames are where the first network was worst, by three hundred centipawns, so they get a
        // third of the games rather than the sliver self-play produces on its own.
        double endgameFraction = 0.35;
        boolean local = false;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

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
                case "--endgame-fraction" ->
                        endgameFraction = Double.parseDouble(value(args, ++i, "--endgame-fraction"));
                case "--local" -> local = true;
                case "--concurrency" -> threads = Integer.parseInt(value(args, ++i, "--concurrency"));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (local) {
            return generateHere(out, targetSamples, gamesPerJob, depth, seed, endgameFraction,
                    threads, append);
        }

        long written = 0;
        long endgameWritten = 0;
        long batches = 0;
        int nextJobId = 0;

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

            System.out.printf("target %,d samples, %d games per job at depth %d, %.0f%% from endgames%n",
                    targetSamples, gamesPerJob, depth, endgameFraction * 100);

            // The queue is kept to a shallow window and refilled from what has actually arrived, rather
            // than filled once from an estimate. Publishing the whole run up front fixes the
            // composition in advance and gets it wrong for the same reason splitting the threads did:
            // the two kinds of job do not produce the same number of samples, so a fixed proportion of
            // jobs is not a fixed proportion of positions. Refilling makes the queue a control loop,
            // which is a better reason for it to exist than spreading work across machines was.
            nextJobId = topUp(broker, nextJobId, seed, gamesPerJob, depth,
                    endgameFraction, written, endgameWritten);
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
                long produced = body.lines().count();
                written += produced;
                if (looksLikeEndgame(body)) {
                    endgameWritten += produced;
                }

                nextJobId = topUp(broker, nextJobId, seed, gamesPerJob, depth,
                        endgameFraction, written, endgameWritten);

                if (batches % 10 == 0) {
                    System.out.printf("%,d samples in %d batches, %.0f%% endgame%n",
                            written, batches, 100.0 * endgameWritten / Math.max(1, written));
                }
            }

            int abandoned = broker.purge(SelfPlayJob.JOBS_QUEUE);
            if (abandoned > 0) {
                System.out.printf("target reached; abandoned %d unstarted job(s)%n", abandoned);
            }
        }

        System.out.println();
        System.out.printf(Locale.ROOT, "wrote %,d samples in %d batches (%.1f%% endgame) to %s%n",
                written, batches, 100.0 * endgameWritten / Math.max(1, written), out.toAbsolutePath());
        return written > 0 ? 0 : 2;
    }

    /**
     * Jobs are held at a shallow depth so the composition stays steerable.
     *
     * <p>Deep enough that generators never wait for work, shallow enough that a queued job is acted on
     * soon after the decision to queue it. A thousand jobs queued up front cannot be steered at all.
     */
    private static final int QUEUE_WINDOW = 24;

    /** Refills the job queue up to the window, choosing each job's kind from what has arrived. */
    private static int topUp(RabbitBroker broker, int nextJobId, long seed, int gamesPerJob, int depth,
                            double endgameFraction, long written, long endgameWritten)
            throws Exception {
        while (broker.depth(SelfPlayJob.JOBS_QUEUE) < QUEUE_WINDOW) {
            // Before anything has arrived there is nothing to steer by, so the kind alternates in the
            // target proportion by job index and the sample counts take over as soon as they exist.
            boolean fromEndgame = written == 0
                    // Bresenham: the count of endgame jobs tracks the target proportion exactly, so the
                    // opening wave is interleaved rather than grouped.
                    ? (int) ((nextJobId + 1) * endgameFraction) > (int) (nextJobId * endgameFraction)
                    : wantsEndgame((int) endgameWritten, (int) written, endgameFraction);
            broker.publish(SelfPlayJob.JOBS_QUEUE,
                    new SelfPlayJob(nextJobId, seed + nextJobId, gamesPerJob, depth, fromEndgame)
                            .encode());
            nextJobId++;
        }
        return nextJobId;
    }

    /**
     * Whether a batch came from endgame-seeded games, judged by what most of it looks like.
     *
     * <p>Not by the first position: a batch is a slice of a job's output, not the start of a game, so a
     * later batch from an opening job can begin in a played-out ending and would be misread. Every
     * position in an endgame-seeded batch is small, while an opening batch is mostly not, so the
     * majority settles it and a few played-out games cannot swing it.
     *
     * <p>Counting pieces rather than adding a field to the message keeps the body exactly the text the
     * dataset receives, which is the property that stops the two ends disagreeing about the format.
     */
    static boolean looksLikeEndgame(String body) {
        int small = 0;
        int lines = 0;
        for (String line : body.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            lines++;
            int bar = line.indexOf('|');
            String placement = (bar < 0 ? line : line.substring(0, bar)).split(" ")[0];
            int pieces = 0;
            for (int i = 0; i < placement.length(); i++) {
                if (Character.isLetter(placement.charAt(i))) {
                    pieces++;
                }
            }
            if (pieces <= 8) {
                small++;
            }
        }
        return lines > 0 && small * 2 > lines;
    }

    /**
     * Whether the next batch should be seeded from an endgame, given what has been written so far.
     *
     * <p>Decided from the running totals rather than from which thread is asking. An endgame position
     * searches about ten times faster than a middlegame one at the same depth, so splitting the
     * threads by the target fraction produced a dataset that was ninety per cent endgames when
     * thirty-five per cent was asked for. Reading the totals makes the split self-correcting whatever
     * the speed ratio happens to be — including on hardware where it is different.
     */
    static boolean wantsEndgame(int endgameSamples, int totalSamples, double fraction) {
        if (fraction <= 0) {
            return false;
        }
        if (fraction >= 1) {
            return true;
        }
        // At the very start both counts are zero, and the comparison chooses endgames. That is the
        // cheap kind, so the first correction arrives quickly.
        return endgameSamples <= fraction * totalSamples;
    }

    /**
     * Generates on this machine, with no broker.
     *
     * <p>The queue earns its place when generation is spread across machines or run alongside
     * training. Needing RabbitMQ to produce a dataset on one laptop is friction rather than
     * architecture, and it would keep CI out of this entirely.
     */
    private static int generateHere(Path out, int targetSamples, int gamesPerJob, int depth,
                                    long seed, double endgameFraction, int threads, boolean append)
            throws IOException, InterruptedException {

        System.out.printf("generating here: %d thread(s), depth %d, %.0f%% from endgames, "
                        + "target %,d samples%n",
                threads, depth, endgameFraction * 100, targetSamples);

        AtomicInteger written = new AtomicInteger();
        AtomicInteger endgameWritten = new AtomicInteger();
        AtomicInteger batchNumber = new AtomicInteger();
        Object writeLock = new Object();

        try (BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {

            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                long threadSeed = seed + t * 1_000_003L;
                workers[t] = new Thread(() -> {
                    SelfPlayGenerator generator = new SelfPlayGenerator();
                    long localSeed = threadSeed;

                    while (written.get() < targetSamples) {
                        // Decided from what has been written, not from which thread this is. An
                        // endgame position searches about ten times faster than a middlegame one at
                        // the same depth, so splitting the threads by the target fraction produced a
                        // dataset that was ninety per cent endgames when thirty-five was asked for.
                        // Reading the running totals makes the split self-correcting whatever the
                        // speed ratio turns out to be.
                        boolean endgames =
                                wantsEndgame(endgameWritten.get(), written.get(), endgameFraction);

                        List<String> starts = endgames
                                ? EndgameSeeds.generate(gamesPerJob, localSeed)
                                : OpeningBook.generate(gamesPerJob, 8, localSeed);
                        localSeed += 7919;

                        StringBuilder batch = new StringBuilder(1 << 16);
                        int produced = 0;
                        for (String start : starts) {
                            for (SelfPlaySample sample : generator.play(start, depth)) {
                                batch.append(sample.encode()).append('\n');
                                produced++;
                            }
                        }
                        if (produced == 0) {
                            continue;
                        }
                        synchronized (writeLock) {
                            try {
                                writer.write(batch.toString());
                                writer.flush();
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }
                        int total = written.addAndGet(produced);
                        if (endgames) {
                            endgameWritten.addAndGet(produced);
                        }
                        if (batchNumber.incrementAndGet() % 20 == 0) {
                            System.out.printf("%,d samples, %.0f%% endgame%n",
                                    total, 100.0 * endgameWritten.get() / Math.max(1, total));
                        }
                    }
                }, "selfplay-local-" + t);
                workers[t].start();
            }
            for (Thread worker : workers) {
                worker.join();
            }
        }

        System.out.printf("%nwrote %,d samples (%.1f%% endgame) to %s%n", written.get(),
                100.0 * endgameWritten.get() / Math.max(1, written.get()), out.toAbsolutePath());
        return written.get() > 0 ? 0 : 2;
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}

