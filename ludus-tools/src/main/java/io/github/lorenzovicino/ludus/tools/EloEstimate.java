package io.github.lorenzovicino.ludus.tools;

/**
 * Elo difference implied by a match result, with a confidence interval.
 *
 * <p>The interval is the part that matters. "Plus forty Elo" from twenty games says almost nothing;
 * the same figure from two thousand games is a result. Reporting the point estimate alone is how
 * people convince themselves of improvements that were noise.
 *
 * <p>The variance comes from the observed win/draw/loss split rather than a binomial assumption,
 * because draws carry half a point and a match full of draws is far less informative per game than
 * one full of decisive results.
 */
public record EloEstimate(int wins, int draws, int losses, double elo, double margin) {

    private static final double Z_95 = 1.959964;

    /**
     * Floor on the per-game variance, for the same reason as in {@link Sprt}: a match where every
     * game ended the same way has zero observed spread, and reporting "+800 Elo plus or minus zero"
     * from thirteen games would be a confident lie. A quarter is the most one game can contribute, so
     * {@code this / games} is the finest spread a sample of that size can resolve.
     */
    private static final double RESOLUTION_LIMIT = 0.25;

    public static EloEstimate of(int wins, int draws, int losses) {
        int games = wins + draws + losses;
        if (games == 0) {
            return new EloEstimate(0, 0, 0, 0, Double.POSITIVE_INFINITY);
        }

        double w = (double) wins / games;
        double d = (double) draws / games;
        double l = (double) losses / games;
        double score = w + d / 2.0;

        double observed = w * sq(1.0 - score) + d * sq(0.5 - score) + l * sq(score);
        double variance = Math.max(observed, RESOLUTION_LIMIT / games);
        double standardError = Math.sqrt(variance / games);

        double elo = toElo(score);
        double upper = toElo(clampScore(score + Z_95 * standardError));
        double lower = toElo(clampScore(score - Z_95 * standardError));
        return new EloEstimate(wins, draws, losses, elo, (upper - lower) / 2.0);
    }

    public int games() {
        return wins + draws + losses;
    }

    public double scorePercent() {
        int games = games();
        return games == 0 ? 0 : 100.0 * (wins + draws / 2.0) / games;
    }

    /**
     * Elo for a score, saturating rather than diverging. A clean sweep implies an unbounded
     * difference, which is true and useless; reporting a large finite number is more informative
     * than an infinity.
     */
    private static double toElo(double score) {
        if (score <= 0.0) {
            return -800;
        }
        if (score >= 1.0) {
            return 800;
        }
        return -400.0 * Math.log10(1.0 / score - 1.0);
    }

    private static double clampScore(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }

    private static double sq(double x) {
        return x * x;
    }

    @Override
    public String toString() {
        return String.format("%+.1f +/- %.1f Elo   %d-%d-%d (W-D-L) over %d games, %.1f%%",
                elo, margin, wins, draws, losses, games(), scorePercent());
    }
}
