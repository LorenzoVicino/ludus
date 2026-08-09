package io.github.lorenzovicino.ludus.tools;

/**
 * The outcome of a whole match, from the candidate's point of view.
 *
 * @param verdict      the verdict as it stood when the test decided, or at the end if it never did
 * @param stoppedEarly whether a bound was crossed rather than the openings running out
 */
public record MatchResult(int wins, int draws, int losses, int illegalByA, int illegalByB,
                          Sprt.Verdict verdict, boolean stoppedEarly) {

    public int games() {
        return wins + draws + losses;
    }
}
