package io.github.lorenzovicino.ludus.tools;

/**
 * Sequential probability ratio test over the result of a match.
 *
 * <p>The point of a sequential test is that it decides when it has seen enough. A fixed-length match
 * either wastes thousands of games establishing something obvious, or stops short of significance on
 * a change worth a couple of Elo. An SPRT accumulates a log-likelihood ratio after every game and
 * stops the moment the evidence crosses a bound.
 *
 * <p>Two hypotheses frame it: H0, that the change is worth no more than {@code elo0}, and H1, that
 * it is worth at least {@code elo1}. The bounds come from the error rates you are willing to accept:
 * {@code alpha} for accepting a change that does not help, {@code beta} for rejecting one that does.
 *
 * <p>The LLR here uses the normal approximation, with the variance taken from the observed
 * win/draw/loss split rather than assumed. That is the generalised form in common use, and it needs
 * no guess about how often the two versions will draw — which matters, because that ratio is very
 * different for two engines a hundred Elo apart and two engines five Elo apart.
 */
public final class Sprt {

    /**
     * The largest variance one game can carry, so {@code this / games} is the finest variance a
     * sample of that size can distinguish from zero.
     */
    private static final double RESOLUTION_LIMIT = 0.25;

    private final double scoreH0;
    private final double scoreH1;
    private final double lowerBound;
    private final double upperBound;

    public Sprt(double elo0, double elo1, double alpha, double beta) {
        if (elo1 <= elo0) {
            throw new IllegalArgumentException("elo1 must exceed elo0, got " + elo0 + " and " + elo1);
        }
        this.scoreH0 = expectedScore(elo0);
        this.scoreH1 = expectedScore(elo1);
        this.lowerBound = Math.log(beta / (1 - alpha));
        this.upperBound = Math.log((1 - beta) / alpha);
    }

    /** The expected score per game of a player {@code elo} points stronger. */
    public static double expectedScore(double elo) {
        return 1.0 / (1.0 + Math.pow(10.0, -elo / 400.0));
    }

    /**
     * Log-likelihood ratio for the observed result. Positive evidence favours H1.
     *
     * <p>A sample where every game ended the same way has zero observed variance, and dividing by it
     * would give an infinite ratio. Returning zero instead — "no evidence" — is worse than wrong: a
     * clean sweep of thirteen games is overwhelming evidence, and reporting it as nothing stalls the
     * test exactly when the answer is most obvious.
     *
     * <p>So the variance is floored rather than special-cased. The most a single game can contribute
     * is a quarter, so {@code 0.25 / games} is the finest variance a sample of this size can resolve:
     * below that, one anomalous game would have moved it more. Flooring there keeps a sweep decisive
     * without inventing certainty, and leaves any sample with a normal spread untouched.
     */
    public double logLikelihoodRatio(int wins, int draws, int losses) {
        int games = wins + draws + losses;
        if (games == 0) {
            return 0.0;
        }
        double w = (double) wins / games;
        double d = (double) draws / games;
        double l = (double) losses / games;

        double mean = w + d / 2.0;
        double observed = w * sq(1.0 - mean) + d * sq(0.5 - mean) + l * sq(mean);
        double variance = Math.max(observed, RESOLUTION_LIMIT / games);

        return games * (scoreH1 - scoreH0) * (2 * mean - scoreH0 - scoreH1) / (2 * variance);
    }

    public Verdict verdict(int wins, int draws, int losses) {
        double llr = logLikelihoodRatio(wins, draws, losses);
        if (llr >= upperBound) {
            return Verdict.H1_ACCEPTED;
        }
        if (llr <= lowerBound) {
            return Verdict.H0_ACCEPTED;
        }
        return Verdict.INCONCLUSIVE;
    }

    public double lowerBound() {
        return lowerBound;
    }

    public double upperBound() {
        return upperBound;
    }

    public enum Verdict {
        /** The change is at least as good as elo1 claims. Land it. */
        H1_ACCEPTED,
        /** The change is no better than elo0 allows. Drop it. */
        H0_ACCEPTED,
        /** Not enough evidence either way. Keep playing. */
        INCONCLUSIVE
    }

    private static double sq(double x) {
        return x * x;
    }
}
