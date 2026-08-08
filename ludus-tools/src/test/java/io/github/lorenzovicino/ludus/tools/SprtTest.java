package io.github.lorenzovicino.ludus.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SprtTest {

    private final Sprt sprt = new Sprt(0, 10, 0.05, 0.05);

    @Test
    void equalStrengthScoresHalf() {
        assertEquals(0.5, Sprt.expectedScore(0), 1e-9);
    }

    @Test
    void expectedScoreFollowsTheEloCurve() {
        // The definition of the scale: 400 points is a ten-to-one expectation.
        assertEquals(10.0 / 11.0, Sprt.expectedScore(400), 1e-9);
        assertEquals(1.0 / 11.0, Sprt.expectedScore(-400), 1e-9);
        assertTrue(Sprt.expectedScore(100) > Sprt.expectedScore(50));
    }

    @Test
    void boundsAreSymmetricWhenTheErrorRatesAre() {
        assertEquals(-sprt.lowerBound(), sprt.upperBound(), 1e-9);
        assertTrue(sprt.upperBound() > 0);
    }

    @Test
    void noGamesIsNoEvidence() {
        assertEquals(0.0, sprt.logLikelihoodRatio(0, 0, 0), 1e-9);
        assertEquals(Sprt.Verdict.INCONCLUSIVE, sprt.verdict(0, 0, 0));
    }

    @Test
    void aResultWithNoVarianceStaysFinite() {
        // All draws: the observed variance is zero and an unfloored ratio would divide by it.
        double llr = sprt.logLikelihoodRatio(0, 40, 0);
        assertTrue(Double.isFinite(llr), () -> "Expected a finite ratio, got " + llr);
        assertTrue(llr < 0, "Forty straight draws is evidence against a ten Elo gain, not for it");
        assertEquals(Sprt.Verdict.INCONCLUSIVE, sprt.verdict(0, 40, 0),
                "It is weak evidence, though — forty draws should not decide the test");
    }

    @Test
    void aCleanSweepIsTreatedAsStrongEvidence() {
        // Thirteen wins from thirteen games has zero observed variance too. Reporting that as "no
        // evidence" would stall the test exactly where the answer is most obvious, which is what an
        // earlier version of this class did.
        double llr = sprt.logLikelihoodRatio(13, 0, 0);
        assertTrue(llr > 0, () -> "A sweep must favour H1, got " + llr);
        assertEquals(Sprt.Verdict.H1_ACCEPTED, sprt.verdict(13, 0, 0));
    }

    @Test
    void aSweepOfLossesIsStrongEvidenceTheOtherWay() {
        assertEquals(Sprt.Verdict.H0_ACCEPTED, sprt.verdict(0, 0, 13));
    }

    @Test
    void winningResultsPushTheRatioUp() {
        assertTrue(sprt.logLikelihoodRatio(60, 20, 20) > 0);
        assertTrue(sprt.logLikelihoodRatio(120, 40, 40) > sprt.logLikelihoodRatio(60, 20, 20),
                "Twice the games at the same rate is twice the evidence");
    }

    @Test
    void losingResultsPushTheRatioDown() {
        assertTrue(sprt.logLikelihoodRatio(20, 20, 60) < 0);
    }

    @Test
    void aDecisiveMatchAcceptsTheNewVersion() {
        assertEquals(Sprt.Verdict.H1_ACCEPTED, sprt.verdict(200, 50, 20));
    }

    @Test
    void aDecisiveLossRejectsIt() {
        assertEquals(Sprt.Verdict.H0_ACCEPTED, sprt.verdict(20, 50, 200));
    }

    @Test
    void aShortMatchDecidesNothing() {
        // Four games cannot separate zero from ten Elo however they fall, and a test that claimed
        // otherwise would let noise land patches.
        assertEquals(Sprt.Verdict.INCONCLUSIVE, sprt.verdict(4, 0, 0));
        assertEquals(Sprt.Verdict.INCONCLUSIVE, sprt.verdict(2, 1, 1));
    }

    @Test
    void hypothesesMustBeOrdered() {
        assertThrows(IllegalArgumentException.class, () -> new Sprt(10, 0, 0.05, 0.05));
        assertThrows(IllegalArgumentException.class, () -> new Sprt(5, 5, 0.05, 0.05));
    }

    @Test
    void widerBoundsGatherEvidenceFaster() {
        // Separating 0 from 30 Elo takes far fewer games than separating 0 from 10, because each
        // game speaks more clearly about a bigger claim.
        Sprt narrow = new Sprt(0, 10, 0.05, 0.05);
        Sprt wide = new Sprt(0, 30, 0.05, 0.05);
        assertTrue(wide.logLikelihoodRatio(60, 20, 20) > narrow.logLikelihoodRatio(60, 20, 20));
    }
}
