package io.github.lorenzovicino.ludus.tools.dist;

import io.github.lorenzovicino.ludus.tools.EloEstimate;
import io.github.lorenzovicino.ludus.tools.MatchResult;
import io.github.lorenzovicino.ludus.tools.MatchTally;
import io.github.lorenzovicino.ludus.tools.OpeningBook;
import io.github.lorenzovicino.ludus.tools.Sprt;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands out openings, collects results, and decides when the test has an answer.
 *
 * <pre>
 * java -jar ludus-match.jar coordinator --pairs 250 --sprt 0 10
 * </pre>
 *
 * <p>Knows nothing about the workers: how many there are, which machines they are on, or whether one
 * just died. It publishes jobs and reads results, and the broker deals with the rest. That is the
 * point of putting one in the middle.
 *
 * <p>Exits 0 when the candidate is accepted, 1 when rejected, 2 when the openings ran out without a
 * verdict, and 3 on a setup error — the same contract as the in-process runner, so a workflow can
 * gate on either without caring which was used.
 */
public final class CoordinatorMain {

    private CoordinatorMain() {
    }

    public static int run(String[] args) throws Exception {
        String uri = RabbitTransport.DEFAULT_URI;
        int pairs = 150;
        int openingPlies = 8;
        long seed = 20260808L;
        double elo0 = 0;
        double elo1 = 10;
        double alpha = 0.05;
        double beta = 0.05;
        boolean stopOnVerdict = true;
        int resultTimeoutMinutes = 30;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--broker" -> uri = value(args, ++i, "--broker");
                case "--pairs" -> pairs = Integer.parseInt(value(args, ++i, "--pairs"));
                case "--opening-plies" -> openingPlies = Integer.parseInt(value(args, ++i, "--opening-plies"));
                case "--seed" -> seed = Long.parseLong(value(args, ++i, "--seed"));
                case "--alpha" -> alpha = Double.parseDouble(value(args, ++i, "--alpha"));
                case "--beta" -> beta = Double.parseDouble(value(args, ++i, "--beta"));
                case "--fixed" -> stopOnVerdict = false;
                case "--timeout" -> resultTimeoutMinutes = Integer.parseInt(value(args, ++i, "--timeout"));
                case "--sprt" -> {
                    elo0 = Double.parseDouble(value(args, ++i, "--sprt elo0"));
                    elo1 = Double.parseDouble(value(args, ++i, "--sprt elo1"));
                }
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        List<String> openings = OpeningBook.generate(pairs, openingPlies, seed);
        Sprt sprt = new Sprt(elo0, elo1, alpha, beta);
        AtomicBoolean stop = new AtomicBoolean();
        MatchTally tally = new MatchTally(sprt, stopOnVerdict, stop);

        try (RabbitTransport transport = new RabbitTransport(uri, 1)) {
            // Anything left over from a previous run would be counted into this match.
            int stale = transport.purgeJobs();
            if (stale > 0) {
                System.out.printf("purged %d stale job(s) from a previous run%n", stale);
            }

            System.out.printf("publishing %d opening pairs to %s%n", openings.size(), uri);
            for (int i = 0; i < openings.size(); i++) {
                transport.submitJob(new MatchJob(i, openings.get(i)));
            }
            System.out.println("waiting for workers");

            int expected = openings.size();
            int received = 0;
            Duration patience = Duration.ofMinutes(resultTimeoutMinutes);

            while (received < expected && !stop.get()) {
                MatchTransport.Delivery<MatchTransport.Completed> delivery =
                        transport.nextResult(patience);
                if (delivery == null) {
                    System.err.printf("no result in %d minutes; is a worker running?%n",
                            resultTimeoutMinutes);
                    break;
                }
                tally.record(delivery.value().outcome());
                delivery.ack();
                received++;
            }

            if (stop.get()) {
                // The test has its answer, so the jobs still queued are work nobody needs done.
                int abandoned = transport.purgeJobs();
                System.out.printf("verdict reached; abandoned %d unplayed opening(s)%n", abandoned);
            }
        }

        MatchResult result = tally.result();
        EloEstimate estimate = EloEstimate.of(result.wins(), result.draws(), result.losses());

        System.out.println();
        System.out.println("=".repeat(72));
        System.out.println("candidate relative to baseline: " + estimate);
        System.out.printf("LLR %+.2f against bounds [%.2f, %.2f]%n",
                sprt.logLikelihoodRatio(result.wins(), result.draws(), result.losses()),
                sprt.lowerBound(), sprt.upperBound());
        System.out.println("verdict: " + result.verdict());
        if (result.illegalByA() > 0 || result.illegalByB() > 0) {
            System.out.printf("ILLEGAL MOVES  candidate: %d  baseline: %d%n",
                    result.illegalByA(), result.illegalByB());
        }
        System.out.println("=".repeat(72));

        return switch (result.verdict()) {
            case H1_ACCEPTED -> 0;
            case H0_ACCEPTED -> 1;
            case INCONCLUSIVE -> 2;
        };
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
