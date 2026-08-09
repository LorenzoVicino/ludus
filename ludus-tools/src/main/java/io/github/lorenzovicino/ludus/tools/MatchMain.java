package io.github.lorenzovicino.ludus.tools;

import io.github.lorenzovicino.ludus.tools.dist.CoordinatorMain;
import io.github.lorenzovicino.ludus.tools.dist.WorkerMain;
import io.github.lorenzovicino.ludus.tools.selfplay.CollectorMain;
import io.github.lorenzovicino.ludus.tools.selfplay.GeneratorMain;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for the match runner, in three modes.
 *
 * <pre>
 * ludus-match local       everything on this machine, threads and subprocesses
 * ludus-match coordinator hands out openings over a broker and collects results
 * ludus-match worker      plays openings somebody else scheduled
 * </pre>
 *
 * <p>{@code local} is the default and is what a single machine should use: it needs nothing running
 * and no configuration. The other two exist because one SPRT match is 300 to 500 games, and the wall
 * time of a match is what limits how quickly patches can be evaluated.
 *
 * <p>All three exit the same way — 0 accepted, 1 rejected, 2 inconclusive, 3 error — so a workflow can
 * gate a patch on the result without caring which mode produced it.
 *
 * <pre>
 * java -jar ludus-match.jar local \
 *     --engine-a "java -jar build/candidate.jar" \
 *     --engine-b "java -jar build/baseline.jar" \
 *     --pairs 100 --movetime 100 --concurrency 8 --sprt 0 10
 * </pre>
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
            System.exit(dispatch(args));
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println();
            usage();
            System.exit(EXIT_ERROR);
        } catch (Exception e) {
            // The whole chain, not just toString(). A broker refusing a connection throws an
            // IOException whose message is null, and "error: java.io.IOException" tells nobody
            // anything — the reason is always further down the chain.
            System.err.println("error: " + describe(e));
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                System.err.println("  caused by: " + describe(cause));
            }
            System.exit(EXIT_ERROR);
        }
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getName()
                : throwable.getClass().getSimpleName() + ": " + message;
    }

    private static int dispatch(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            usage();
            return args.length == 0 ? EXIT_ERROR : EXIT_ACCEPTED;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return switch (args[0]) {
            case "local" -> runLocal(rest);
            case "worker" -> WorkerMain.run(rest);
            case "coordinator" -> CoordinatorMain.run(rest);
            case "generate" -> GeneratorMain.run(rest);
            case "collect" -> CollectorMain.run(rest);
            // Options straight away is the original single-mode invocation, kept working.
            default -> args[0].startsWith("--")
                    ? runLocal(args)
                    : fail("unknown mode " + args[0]);
        };
    }

    private static int fail(String message) {
        throw new IllegalArgumentException(message);
    }

    private static int runLocal(String[] args) throws Exception {
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
        int openingPlies = 8;
        Map<String, String> optionsA = new LinkedHashMap<>();
        Map<String, String> optionsB = new LinkedHashMap<>();

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--engine-a" -> engineA = require(args, ++i, "--engine-a");
                case "--engine-b" -> engineB = require(args, ++i, "--engine-b");
                case "--option-a" -> addOption(optionsA, require(args, ++i, "--option-a"));
                case "--option-b" -> addOption(optionsB, require(args, ++i, "--option-b"));
                case "--book" -> book = Path.of(require(args, ++i, "--book"));
                case "--pairs" -> pairs = Integer.parseInt(require(args, ++i, "--pairs"));
                case "--movetime" -> moveTime = Long.parseLong(require(args, ++i, "--movetime"));
                case "--concurrency" -> concurrency = Integer.parseInt(require(args, ++i, "--concurrency"));
                case "--max-plies" -> maxPlies = Integer.parseInt(require(args, ++i, "--max-plies"));
                case "--seed" -> seed = Long.parseLong(require(args, ++i, "--seed"));
                case "--opening-plies" -> openingPlies = Integer.parseInt(require(args, ++i, "--opening-plies"));
                case "--fixed" -> stopOnVerdict = false;
                case "--sprt" -> {
                    elo0 = Double.parseDouble(require(args, ++i, "--sprt elo0"));
                    elo1 = Double.parseDouble(require(args, ++i, "--sprt elo1"));
                }
                case "--alpha" -> alpha = Double.parseDouble(require(args, ++i, "--alpha"));
                case "--beta" -> beta = Double.parseDouble(require(args, ++i, "--beta"));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        if (engineA == null || engineB == null) {
            throw new IllegalArgumentException("both --engine-a and --engine-b are required");
        }

        List<String> openings = book == null
                ? OpeningBook.generate(pairs, openingPlies, seed)
                : OpeningBook.load(book, seed);

        Sprt sprt = new Sprt(elo0, elo1, alpha, beta);
        MatchRunner.Config config = new MatchRunner.Config(
                pairs, moveTime, maxPlies, concurrency, stopOnVerdict, Duration.ofSeconds(30));

        System.out.printf("A: %s%nB: %s%n", engineA, engineB);
        System.out.printf("%d opening pairs, %d ms per move, concurrency %d%n",
                Math.min(pairs, openings.size()), moveTime, concurrency);
        System.out.printf("SPRT H0 %.1f vs H1 %.1f Elo, alpha %.2f beta %.2f%n%n",
                elo0, elo1, alpha, beta);

        if (!optionsA.isEmpty()) {
            System.out.println("A options: " + optionsA);
        }
        if (!optionsB.isEmpty()) {
            System.out.println("B options: " + optionsB);
        }

        MatchRunner runner = new MatchRunner(
                split(engineA), split(engineB), optionsA, optionsB, openings, config, sprt);
        MatchResult result = runner.run();

        EloEstimate estimate = EloEstimate.of(result.wins(), result.draws(), result.losses());

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("A relative to B: " + estimate);
        System.out.printf("LLR %+.2f against bounds [%.2f, %.2f]%n",
                sprt.logLikelihoodRatio(result.wins(), result.draws(), result.losses()),
                sprt.lowerBound(), sprt.upperBound());
        System.out.printf("stopped: %s%n",
                result.stoppedEarly() ? "a bound was crossed" : "the book ran out");
        System.out.println("verdict: " + describe(result.verdict()));
        if (result.illegalByA() > 0 || result.illegalByB() > 0) {
            System.out.printf("ILLEGAL MOVES  A: %d  B: %d%n", result.illegalByA(), result.illegalByB());
        }
        System.out.println("=".repeat(72));

        return switch (result.verdict()) {
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

    private static List<String> split(String command) {
        return Arrays.asList(command.trim().split("\\s+"));
    }

    /**
     * Parses {@code Name=value}, which is how one build is made to play another with a different
     * evaluation loaded rather than needing two jars that differ only in a setting.
     */
    private static void addOption(Map<String, String> options, String assignment) {
        int equals = assignment.indexOf('=');
        if (equals <= 0) {
            throw new IllegalArgumentException("Options look like Name=value, got " + assignment);
        }
        options.put(assignment.substring(0, equals).trim(), assignment.substring(equals + 1).trim());
    }

    private static String require(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }

    private static void usage() {
        System.err.println("""
                usage: ludus-match <mode> [options]

                MODES
                  local        run the whole match here (default if options come first)
                  coordinator  hand out openings over a broker and collect results
                  worker       play openings a coordinator scheduled
                  collect      schedule self-play and write the training dataset as it arrives
                  generate     play self-play games and publish labelled positions

                LOCAL
                  --engine-a CMD     command that launches the candidate
                  --engine-b CMD     command that launches the baseline
                  --option-a N=V     UCI option for the candidate, repeatable
                  --option-b N=V     UCI option for the baseline, repeatable
                  --book PATH        EPD or FEN book. Omit it and one is generated
                  --opening-plies K  random plies when generating a book (default 8)
                  --pairs N          opening pairs, two games each (default 100)
                  --movetime MS      fixed allowance per move (default 100)
                  --concurrency K    games in parallel (default: cores / 3)
                  --max-plies N      draw a game that reaches this length (default 300)
                  --sprt E0 E1       hypothesis bounds in Elo (default 0 10)
                  --alpha A          false-accept rate (default 0.05)
                  --beta B           false-reject rate (default 0.05)
                  --seed S           book seed (default 20260808)
                  --fixed            play every opening instead of stopping at a bound

                COORDINATOR
                  --broker URI       AMQP URI (default amqp://guest:guest@localhost:5672/)
                  --pairs, --sprt, --alpha, --beta, --seed, --opening-plies, --fixed as above
                  --timeout MIN      give up if no result arrives for this long (default 30)

                WORKER
                  --engine-a CMD, --engine-b CMD   both jars must exist on this machine
                  --broker URI       AMQP URI
                  --movetime MS, --max-plies N     as above
                  --concurrency K    engine pairs on this machine (default 1)
                  --idle-timeout S   exit after this long with no work (default 120)

                COLLECT
                  --out PATH         dataset file (default training/data/selfplay.txt)
                  --samples N        stop once this many positions are written (default 100000)
                  --games-per-job N  games per queued job (default 50)
                  --depth D          search depth per move (default 6)
                  --append           add to the dataset instead of replacing it
                  --broker URI, --seed S, --idle-timeout MIN

                GENERATE
                  --concurrency K    games in parallel on this machine (default: cores / 2)
                  --broker URI, --idle-timeout S

                exit: 0 accepted, 1 rejected, 2 inconclusive, 3 error

                Engine commands are split on whitespace, so paths must not contain spaces.
                """);
    }
}
