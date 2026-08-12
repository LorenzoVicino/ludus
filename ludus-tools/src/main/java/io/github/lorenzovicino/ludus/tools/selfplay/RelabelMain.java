package io.github.lorenzovicino.ludus.tools.selfplay;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Material;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Replaces labels that chess theory settles with the answers it settles them to.
 *
 * <pre>
 * java -jar ludus-match.jar relabel --in build/selfplay.txt --out build/selfplay-clean.txt
 * </pre>
 *
 * <h2>Why this is not cheating</h2>
 *
 * <p>A label normally says "this is what a ten-ply search concluded". For a position where neither side
 * can deliver mate, no search is needed and none should be trusted: the position is drawn, the score is
 * zero, and that is knowledge the search did not have. Writing it in is the same act as consulting a
 * tablebase, done for the one case where the table fits in a sentence.
 *
 * <p>It exists because the evaluation used to score such positions at up to +417 centipawns, and every
 * label produced by searching with it inherited that. On the dataset in hand, <strong>89,200 positions —
 * 13.88% — carried a mean absolute error of 235 centipawns against a true value of zero.</strong>
 *
 * <h2>What it does not fix</h2>
 *
 * <p>Only the positions that are already drawn. A position <em>two</em> moves from a dead draw was
 * labelled by a search that misjudged what lay beyond it, and there is no closed form for what it should
 * have said instead. Regenerating with a corrected evaluation is the only clean answer to that, and this
 * tool is not a substitute for it — it removes the part that is exactly known, cheaply, and leaves the
 * rest visible.
 *
 * <p>The game result is left untouched, and does not need touching: a game that reaches material
 * insufficient to mate cannot be won, so every one of those 89,200 positions was already recorded as a
 * draw. That was checked rather than assumed, and finding a single win among them would have meant a bug
 * somewhere else entirely.
 */
public final class RelabelMain {

    private RelabelMain() {
    }

    public static int run(String[] args) throws Exception {
        Path in = null;
        Path out = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--in" -> in = Path.of(value(args, ++i, "--in"));
                case "--out" -> out = Path.of(value(args, ++i, "--out"));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (in == null || out == null) {
            throw new IllegalArgumentException("both --in and --out are required");
        }

        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        long total = 0;
        long rewritten = 0;
        long alreadyZero = 0;
        long errorBefore = 0;
        int worst = 0;
        long unexpectedResults = 0;

        try (var lines = Files.lines(in, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(out, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (line.isBlank()) {
                    continue;
                }
                total++;

                SelfPlaySample sample;
                try {
                    sample = SelfPlaySample.decode(line);
                } catch (RuntimeException malformed) {
                    // Passed through rather than dropped: a line this tool cannot read is a line it has
                    // no business deleting, and silently shrinking a dataset is worse than leaving it be.
                    writer.write(line);
                    writer.write('\n');
                    continue;
                }

                Board board = Board.fromFen(sample.fen());
                if (!Material.isInsufficientToMate(board)) {
                    writer.write(line);
                    writer.write('\n');
                    continue;
                }

                if (sample.result() != SelfPlaySample.DRAW) {
                    // Impossible if the generator is right: no capture or promotion can follow, so the
                    // game had to end level. Counted rather than corrected, because it would mean a bug
                    // to go and find rather than a label to quietly fix.
                    unexpectedResults++;
                }

                int magnitude = Math.abs(sample.score());
                if (magnitude == 0) {
                    alreadyZero++;
                } else {
                    rewritten++;
                    errorBefore += magnitude;
                    worst = Math.max(worst, magnitude);
                }

                writer.write(new SelfPlaySample(sample.fen(), 0, sample.result()).encode());
                writer.write('\n');
            }
        }

        long affected = rewritten + alreadyZero;
        System.out.printf(Locale.ROOT, "%,d positions read%n", total);
        System.out.printf(Locale.ROOT, "%,d cannot mate (%.2f%%), of which %,d already read zero%n",
                affected, total == 0 ? 0 : 100.0 * affected / total, alreadyZero);
        if (rewritten > 0) {
            System.out.printf(Locale.ROOT,
                    "%,d rewritten to zero: mean error was %d cp, worst %d cp%n",
                    rewritten, errorBefore / rewritten, worst);
        }
        if (unexpectedResults > 0) {
            System.err.printf("%,d of them were not recorded as draws, which should be impossible - "
                    + "left alone, and worth investigating%n", unexpectedResults);
        }
        System.out.println("wrote " + out.toAbsolutePath());
        return 0;
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
