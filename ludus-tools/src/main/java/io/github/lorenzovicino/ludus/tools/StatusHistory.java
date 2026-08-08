package io.github.lorenzovicino.ludus.tools;

import java.util.List;

/**
 * The facts about the project that no build step can measure.
 *
 * <p>Perft counts and test totals are recomputed on every run, because they can be. A match result
 * cannot: playing two hundred games takes minutes and needs both versions built side by side, so it
 * is run deliberately and its outcome recorded here.
 *
 * <p>This is typed Java rather than a JSON file on purpose. It is edited once per milestone by
 * whoever ran the match, and having the compiler check the shape beats parsing a file that only this
 * program reads.
 */
final class StatusHistory {

    private StatusHistory() {
    }

    static final String ENGINE = "ludus";
    static final String VERSION = "0.3.0";
    static final String CURRENT_MILESTONE = "M3";

    /**
     * @param moveTimeMillis fixed allowance per move — see the match runner on why a fixed time
     *                       rather than a running clock
     */
    record MatchResult(String baseline, String candidate, double elo, double margin,
                       int wins, int draws, int losses, double llr, int sprtCrossedAt,
                       long moveTimeMillis) {

        int games() {
            return wins + draws + losses;
        }

        double percent() {
            return 100.0 * (wins + draws / 2.0) / games();
        }
    }

    record Milestone(String id, String title, String criterion, boolean done) {
    }

    /**
     * The most recent measured match. Reported as one figure rather than a chart: with a single data
     * point a chart would be decoration, and a chart earns its place once M3 adds a second.
     */
    static final MatchResult LATEST_MATCH = new MatchResult(
            "M2", "M3", 181.7, 39.5, 195, 54, 51, 6.82, 85, 100);

    static final List<Milestone> MILESTONES = List.of(
            new Milestone("M0",
                    "Board, magic bitboards, move generation",
                    "The full perft suite passes. Nothing else in the engine is worth trusting "
                            + "until it does.",
                    true),
            new Milestone("M1",
                    "Alpha-beta search, hand-crafted evaluation, UCI",
                    "Plays a complete legal game against a GUI without ever proposing an illegal "
                            + "move.",
                    true),
            new Milestone("M2",
                    "Quiescence, transposition table, killers, history, SEE",
                    "Beats M1 by SPRT. This is the Elo baseline the network gets measured against.",
                    true),
            new Milestone("M3",
                    "Principal variation search, null move, late move reductions",
                    "Perft still correct, and every patch SPRT-positive on its own. Null move was "
                            + "measured and rejected before PVS made it able to fire at all.",
                    true),
            new Milestone("M4",
                    "NNUE inference, first trained network",
                    "Accumulator matches a full recomputation bit for bit, Java inference matches "
                            + "PyTorch, and SPRT beats M3.",
                    false),
            new Milestone("M5",
                    "Vector API, tuning, halfKP features",
                    "Higher nodes per second at equal Elo, then higher Elo.",
                    false),
            new Milestone("M6",
                    "Self-updating status page",
                    "This page rewrites itself from CI on every push and nightly, with no manual "
                            + "step.",
                    true));
}
