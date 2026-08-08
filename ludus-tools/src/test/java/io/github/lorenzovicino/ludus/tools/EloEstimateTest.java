package io.github.lorenzovicino.ludus.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EloEstimateTest {

    @Test
    void anEvenMatchIsZeroElo() {
        assertEquals(0.0, EloEstimate.of(50, 0, 50).elo(), 1e-6);
        assertEquals(0.0, EloEstimate.of(0, 100, 0).elo(), 1e-6);
    }

    @Test
    void seventyFivePercentIsAboutTwoHundredElo() {
        // A well-known reference point on the scale, useful as a sanity check on the conversion.
        assertEquals(191.0, EloEstimate.of(75, 0, 25).elo(), 2.0);
    }

    @Test
    void aCleanSweepSaturatesInsteadOfDiverging() {
        // The honest answer is "unbounded", which is useless in a report. A large finite number says
        // the same thing and still prints.
        assertEquals(800.0, EloEstimate.of(40, 0, 0).elo(), 1e-6);
        assertEquals(-800.0, EloEstimate.of(0, 0, 40).elo(), 1e-6);
    }

    @Test
    void aSweepStillCarriesUncertainty() {
        // Thirteen wins from thirteen games has no observed spread, and an unfloored interval would
        // read "+800 plus or minus zero" — a confident lie from a small sample.
        EloEstimate sweep = EloEstimate.of(13, 0, 0);
        assertTrue(sweep.margin() > 50,
                () -> "A thirteen-game sweep is not precise: " + sweep.margin());
        assertTrue(Double.isFinite(sweep.margin()));
    }

    @Test
    void moreGamesNarrowTheInterval() {
        double few = EloEstimate.of(30, 20, 10).margin();
        double many = EloEstimate.of(300, 200, 100).margin();
        assertTrue(many < few,
                () -> "Ten times the games should tighten the interval: " + few + " then " + many);
    }

    @Test
    void drawsCarryHalfAPoint() {
        assertEquals(50.0, EloEstimate.of(0, 10, 0).scorePercent(), 1e-9);
        // Ten wins and ten draws out of twenty games is fifteen points, so 75 percent.
        assertEquals(75.0, EloEstimate.of(10, 10, 0).scorePercent(), 1e-9);
        assertEquals(50.0, EloEstimate.of(5, 10, 5).scorePercent(), 1e-9);
    }

    @Test
    void gamesAreCounted() {
        EloEstimate estimate = EloEstimate.of(7, 3, 2);
        assertEquals(12, estimate.games());
    }

    @Test
    void anEmptyMatchClaimsNothing() {
        EloEstimate estimate = EloEstimate.of(0, 0, 0);
        assertEquals(0, estimate.games());
        assertTrue(Double.isInfinite(estimate.margin()),
                "With no games the interval must be unbounded, not zero");
    }

    @Test
    void theSummaryReadsAsAResult() {
        String text = EloEstimate.of(60, 20, 20).toString();
        assertTrue(text.contains("+/-"), () -> "The interval belongs in the summary: " + text);
        assertTrue(text.contains("60-20-20"), () -> "So does the raw split: " + text);
    }
}
