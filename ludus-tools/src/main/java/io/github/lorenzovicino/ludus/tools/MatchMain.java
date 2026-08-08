package io.github.lorenzovicino.ludus.tools;

import io.github.lorenzovicino.ludus.core.Board;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Command line entry point for the match runner.
 *
 * <pre>
 * java -jar ludus-match.jar \
 *     --engine-a "java -jar build/ludus-new.jar" \
 *     --engine-b "java -jar build/ludus-old.jar" \
 *     --book openings.epd \
 *     --pairs 150 --movetime 100 --concurrency 8 \
 *     --sprt 0 10
 * </pre>
 *
 * <p>Exits 0 when the new version is accepted, 1 when it is rejected, 2 when the match ended without
 * a verdict, and 3 on a usage or setup error. That mapping is what lets CI gate a patch on the
 * result rather than on somebody reading the output.
 */
public final class MatchMain {

    private static final int EXIT_ACCEPTED = 0;
    private static final int EXIT_REJECTED = 1;
    private static final int EXIT_INCONCLUSIVE = 2;
    private static final int EXIT_ERROR = 3;

    private MatchMain() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            usage();
            System.exit(EXIT_ERROR);
        } catch (IOException | InterruptedException e) {
            System.err.println("error: " + e);
            System.exit(EXIT_ERROR);
        }
    }

    private static int run(String[] args) throws IOException, InterruptedException {
        String engineA = null;
        String engineB = null;
        Path book = null;
        int pairs = 100;
        long moveTime = 100;
        int concurrency = Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
        int maxPlies = 300;
        double elo0 = 0;
        double elo1 = 10;
        double alpha = 0.05;
        double beta = 0.05;
        long seed = 20260808L;
        boolean stopOnVerdict = true;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--engine-a" -> engineA = require(args, ++i, "--engine-a");
                case "--engine-b" -> engineB = require(args, ++i, "--engine-b");
                case "--book" -> book = Path.of(require(args, ++i, "--book"));
                case "--pairs" -> pairs = Integer.parseInt(require(args, ++i, "--pairs"));
                case "--movetime" -> moveTime = Long.parseLong(require(args, ++i, "--movetime"));
                case "--concurrency" -> concurrency = Integer.parseInt(require(args, ++i, "--concurrency"));
                case "--max-plies" -> maxPlies = Integer.parseInt(require(args, ++i, "--max-plies"));
                case "--seed" -> seed = Long.parseLong(require(args, ++i, "--seed"));
                case "--fixed" -> stopOnVerdict = false;
                case "--sprt" -> {
                    elo0 = Double.parseDouble(require(args, ++i, "--sprt elo0"));
                    elo1 = Double.parseDouble(require(args, ++i, "--sprt elo1"));
                }
                case "--alpha" -> alpha = Double.parseDouble(require(args, ++i, "--alpha"));
                case "--beta" -> beta = Double.parseDouble(require(args, ++i, "--beta"));
                case "--help", "-h" -> {
                    usage();
                    return EXIT_ACCEPTED;
                }
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        if (engineA == null || engineB == null) {
            throw new IllegalArgumentException("both --engine-a and --engine-b are required");
        }

        List<String> openings = book == null
                ? List.of(Board.START_FEN)
                : loadBook(book, seed);

        Sprt sprt = new Sprt(elo0, elo1, alpha, beta);
        MatchRunner.Config config = new MatchRunner.Config(
                pairs, moveTime, maxPlies, concurrency, stopOnVerdict, Duration.ofSeconds(30));

        System.out.printf("A: %s%nB: %s%n", engineA, engineB);
        System.out.printf("%d opening pairs, %d ms per move, concurrency %d%n",
                Math.min(pairs, openings.size()), moveTime, concurrency);
        System.out.printf("SPRT H0 %.1f vs H1 %.1f Elo, alpha %.2f beta %.2f%n%n", elo0, elo1, alpha, beta);

        MatchRunner runner = new MatchRunner(
                split(engineA), split(engineB), openings, config, sprt);
        MatchRunner.Result result = runner.run();

        EloEstimate estimate = EloEstimate.of(result.wins(), result.draws(), result.losses());
        Sprt.Verdict verdict = result.verdict();

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("A relative to B: " + estimate);
        System.out.printf("LLR %+.2f against bounds [%.2f, %.2f]%n",
                sprt.logLikelihoodRatio(result.wins(), result.draws(), result.losses()),
                sprt.lowerBound(), sprt.upperBound());
        System.out.printf("stopped: %s%n",
                result.stoppedEarly() ? "a bound was crossed" : "the book ran out");
        System.out.println("verdict: " + describe(verdict));
        if (result.illegalByA() > 0 || result.illegalByB() > 0) {
            System.out.printf("ILLEGAL MOVES  A: %d  B: %d%n", result.illegalByA(), result.illegalByB());
        }
        System.out.println("=".repeat(72));

        return switch (verdict) {
            case H1_ACCEPTED -> EXIT_ACCEPTED;
            case H0_ACCEPTED -> EXIT_REJECTED;
            case INCONCLUSIVE -> EXIT_INCONCLUSIVE;
        };
    }

    private static String describe(Sprt.Verdict verdict) {
        return switch (verdict) {
            case H1_ACCEPTED -> "H1 accepted — A is stronger, the change lands";
            case H0_ACCEPTED -> "H0 accepted — A is not better, the change is dropped";
            case INCONCLUSIVE -> "inconclusive — the match ran out of openings before deciding";
        };
    }

    /**
     * Reads an EPD or FEN book, one position per line, and shuffles it with a fixed seed so a rerun
     * plays the same openings in the same order.
     */
    private static List<String> loadBook(Path path, long seed) throws IOException {
        List<String> positions = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] fields = trimmed.split("\\s+");
            if (fields.length < 4) {
                continue;
            }
            // An EPD line is a FEN's first four fields followed by opcodes; the clocks are optional
            // and anything after them is not ours to interpret.
            String fen = String.join(" ", Arrays.copyOfRange(fields, 0, 4)) + " 0 1";
            try {
                Board.fromFen(fen);
                positions.add(fen);
            } catch (RuntimeException ignored) {
                // Not a position we can parse; skip rather than abandon the book.
            }
        }
        if (positions.isEmpty()) {
            throw new IllegalArgumentException("no usable positions in " + path);
        }
        Collections.shuffle(positions, new Random(seed));
        return positions;
    }

    private static List<String> split(String command) {
        return Arrays.asList(command.trim().split("\\s+"));
    }

    private static String require(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static void usage() {
        System.err.println("""
                usage: ludus-match --engine-a CMD --engine-b CMD [options]

                  --engine-a CMD     command that launches the new version
                  --engine-b CMD     command that launches the baseline
                  --book PATH        EPD or FEN opening book, one position per line
                  --pairs N          opening pairs to play, two games each (default 100)
                  --movetime MS      fixed allowance per move (default 100)
                  --concurrency K    games in parallel (default: cores / 3)
                  --max-plies N      draw a game that reaches this length (default 300)
                  --sprt E0 E1       hypothesis bounds in Elo (default 0 10)
                  --alpha A          false-accept rate (default 0.05)
                  --beta B           false-reject rate (default 0.05)
                  --seed S           book shuffle seed (default 20260808)
                  --fixed            play every opening instead of stopping at a bound,
                                     for when the Elo figure matters more than the verdict

                exit: 0 accepted, 1 rejected, 2 inconclusive, 3 error

                Engine commands are split on whitespace, so paths must not contain spaces.
                """);
    }
}
