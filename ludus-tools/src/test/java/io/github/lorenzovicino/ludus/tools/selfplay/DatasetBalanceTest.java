package io.github.lorenzovicino.ludus.tools.selfplay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The composition of the dataset is a property worth asserting, because getting it wrong is silent.
 *
 * <p>The first version of this scheduler split the generating threads by the target fraction. That
 * looks obviously correct and is not: an endgame position searches roughly ten times faster than a
 * middlegame one at the same depth, so a third of the threads produced nine tenths of the samples. The
 * run finished successfully, reported nothing unusual, and yielded a dataset that was the mirror image
 * of the problem it was built to fix.
 *
 * <p>These tests feed the decision the same lopsided yields and require the composition to come out
 * where it was asked to. {@link #theThreadSplittingPolicyWouldNotPassThis()} runs the old policy
 * through the same simulation, so the property here is demonstrably not free.
 */
class DatasetBalanceTest {

    /** Samples an endgame batch yields per decision, against an opening batch's. Measured: about 10x. */
    private static final int ENDGAME_YIELD = 400;
    private static final int OPENING_YIELD = 40;

    @Test
    @DisplayName("the endgame share converges on the target even when endgames generate ten times faster")
    void balanceSurvivesLopsidedThroughput() {
        double target = 0.35;
        int endgame = 0;
        int total = 0;

        for (int batch = 0; batch < 5_000; batch++) {
            if (CollectorMain.wantsEndgame(endgame, total, target)) {
                endgame += ENDGAME_YIELD;
                total += ENDGAME_YIELD;
            } else {
                total += OPENING_YIELD;
            }
        }

        double share = (double) endgame / total;
        assertTrue(Math.abs(share - target) < 0.02,
                "expected about %.0f%% endgame, got %.1f%%".formatted(target * 100, share * 100));
    }

    @Test
    @DisplayName("a middlegame-heavy target is honoured just as well as an endgame-heavy one")
    void balanceHoldsInTheOtherDirection() {
        for (double target : new double[] {0.1, 0.5, 0.8}) {
            int endgame = 0;
            int total = 0;
            for (int batch = 0; batch < 5_000; batch++) {
                if (CollectorMain.wantsEndgame(endgame, total, target)) {
                    endgame += ENDGAME_YIELD;
                    total += ENDGAME_YIELD;
                } else {
                    total += OPENING_YIELD;
                }
            }
            assertEquals(target, (double) endgame / total, 0.02,
                    "target " + target + " was not reached");
        }
    }

    /**
     * The policy this replaced, kept as a simulation so the test above can be shown to have teeth.
     * Eight of twenty-two threads were told to make endgames — 36% of the threads, and very nearly the
     * 35% asked for, which is exactly why it looked right.
     */
    @Test
    @DisplayName("the thread-splitting policy this replaced misses 35% by a factor of two and a half")
    void theThreadSplittingPolicyWouldNotPassThis() {
        int threads = 22;
        int endgameThreads = (int) Math.round(threads * 0.35);
        assertEquals(8, endgameThreads, "the old split really was close to the target, per thread");

        // Each thread produces batches at a rate set by what it generates, so over a fixed wall clock
        // an endgame thread contributes ten times the samples of an opening one.
        int endgame = endgameThreads * ENDGAME_YIELD;
        int opening = (threads - endgameThreads) * OPENING_YIELD;
        double share = (double) endgame / (endgame + opening);

        // The simulation says about 85%. The run that exposed this measured 91% on 642,605 samples.
        assertTrue(share > 0.80,
                "the old policy should be badly skewed, was %.1f%%".formatted(share * 100));
    }

    @Test
    @DisplayName("the extremes mean what they say")
    void extremesAreAbsolute() {
        assertFalse(CollectorMain.wantsEndgame(0, 0, 0.0), "zero should never seed an endgame");
        assertFalse(CollectorMain.wantsEndgame(0, 1_000_000, 0.0));
        assertTrue(CollectorMain.wantsEndgame(1_000_000, 1_000_000, 1.0), "one should always seed one");
    }

    @Test
    @DisplayName("an empty dataset starts with the cheap kind rather than dividing by zero")
    void startsWithoutDividingByZero() {
        assertTrue(CollectorMain.wantsEndgame(0, 0, 0.35));
    }

    private static final String OPENING_START =
            "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b - - 0 1|12|1";
    private static final String PLAYED_OUT_ENDING = "8/8/4k3/8/8/2K5/6R1/8 w - - 4 40|310|1";
    private static final String SEEDED_ENDING = "8/8/8/1b5k/5K2/2B5/8/8 b - - 4 3|-23|1";

    @Test
    @DisplayName("a batch of seeded endgames is recognised as one")
    void endgameBatchIsRecognised() {
        assertTrue(CollectorMain.looksLikeEndgame(
                String.join("\n", SEEDED_ENDING, SEEDED_ENDING, SEEDED_ENDING)));
    }

    @Test
    @DisplayName("an opening batch is not called an endgame just because its first line is small")
    void aPlayedOutEndingDoesNotRelabelAnOpeningBatch() {
        // The bug this guards: a batch is a slice of a job's output, not the start of a game, so a
        // later batch from an opening job can begin in an ending that was played into. Judging by the
        // first line alone misreads that batch and starves the control loop of endgame jobs.
        String batch = String.join("\n", PLAYED_OUT_ENDING, PLAYED_OUT_ENDING,
                OPENING_START, OPENING_START, OPENING_START, OPENING_START, OPENING_START);
        assertFalse(CollectorMain.looksLikeEndgame(batch),
                "two played-out positions should not outvote five middlegame ones");
    }

    @Test
    @DisplayName("an empty body is not an endgame")
    void emptyBodyIsNotAnEndgame() {
        assertFalse(CollectorMain.looksLikeEndgame(""));
        assertFalse(CollectorMain.looksLikeEndgame("\n\n"));
    }

    @Test
    @DisplayName("a single-line body still classifies")
    void singleLineBodyClassifies() {
        assertTrue(CollectorMain.looksLikeEndgame(SEEDED_ENDING));
        assertFalse(CollectorMain.looksLikeEndgame(OPENING_START));
    }
}
