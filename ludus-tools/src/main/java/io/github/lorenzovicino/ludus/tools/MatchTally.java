package io.github.lorenzovicino.ludus.tools;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Accumulates results, prints progress, and decides when the test has an answer.
 *
 * <p>Shared by the in-process runner and the distributed coordinator so both stop on exactly the same
 * evidence. Thread-safe: results arrive from several workers, or from a broker consumer thread.
 */
public final class MatchTally {

    private final Sprt sprt;
    private final boolean stopOnVerdict;
    private final AtomicBoolean stop;
    private final Object lock = new Object();

    private int wins;
    private int draws;
    private int losses;
    private int illegalByA;
    private int illegalByB;
    private int pairs;

    // The verdict is latched the instant a bound is crossed, together with the tally that crossed it.
    //
    // Recomputing it once every worker has drained would be wrong, and wrong in a way that reads as
    // correct. Games already in flight keep finishing after the stop is signalled, and a single late
    // loss can pull the ratio back inside the bounds — turning a test that had decided into one that
    // reports "inconclusive". The evidence that crossed the bound does not stop being evidence.
    private Sprt.Verdict decidedVerdict;
    private int decidedWins;
    private int decidedDraws;
    private int decidedLosses;

    public MatchTally(Sprt sprt, boolean stopOnVerdict, AtomicBoolean stop) {
        this.sprt = sprt;
        this.stopOnVerdict = stopOnVerdict;
        this.stop = stop;
    }

    public void record(GamePlayer.PairOutcome outcome) {
        synchronized (lock) {
            wins += outcome.wins();
            draws += outcome.draws();
            losses += outcome.losses();
            illegalByA += outcome.illegalByA();
            illegalByB += outcome.illegalByB();
            pairs++;

            double llr = sprt.logLikelihoodRatio(wins, draws, losses);
            System.out.printf("pair %3d  W-D-L %3d-%3d-%3d  LLR %+.2f  [%.2f, %.2f]  %s%n",
                    pairs, wins, draws, losses, llr, sprt.lowerBound(), sprt.upperBound(),
                    EloEstimate.of(wins, draws, losses));

            if (decidedVerdict == null) {
                Sprt.Verdict verdict = sprt.verdict(wins, draws, losses);
                if (verdict != Sprt.Verdict.INCONCLUSIVE) {
                    decidedVerdict = verdict;
                    decidedWins = wins;
                    decidedDraws = draws;
                    decidedLosses = losses;
                    if (stopOnVerdict) {
                        stop.set(true);
                    }
                    System.out.printf("--- bound crossed after %d games: %s ---%n",
                            wins + draws + losses, verdict);
                }
            }
        }
    }

    public MatchResult result() {
        synchronized (lock) {
            if (decidedVerdict == null) {
                return new MatchResult(wins, draws, losses, illegalByA, illegalByB,
                        sprt.verdict(wins, draws, losses), false);
            }
            if (stopOnVerdict) {
                // The tally that crossed the bound is the evidence the verdict rests on.
                return new MatchResult(decidedWins, decidedDraws, decidedLosses,
                        illegalByA, illegalByB, decidedVerdict, true);
            }
            // Fixed length: keep the verdict that was reached, but report the whole match, because
            // the reason to play every opening was to pin the Elo down.
            return new MatchResult(wins, draws, losses, illegalByA, illegalByB, decidedVerdict, false);
        }
    }
}
