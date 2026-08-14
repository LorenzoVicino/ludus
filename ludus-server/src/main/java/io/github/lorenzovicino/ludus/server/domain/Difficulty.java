package io.github.lorenzovicino.ludus.server.domain;

import java.time.Duration;

/**
 * How hard the engine tries, as a level a browser can offer in a dropdown.
 *
 * <p>Expressed as <em>both</em> a depth cap and a time cap, and the engine stops at whichever comes
 * first. Depth alone is unbounded in wall-clock terms — a tactical middlegame at depth 12 can take
 * many seconds while an endgame takes milliseconds — and a request that hangs for a minute is a bug
 * however correct the move is. Time alone makes the level depend on how loaded the server is, so the
 * same setting would play differently at different times of day.
 *
 * <p>The lower levels are deliberately not "the same engine, thinking briefly". They are shallow,
 * which produces the kind of mistake a human recognises as beatable: shallow search misses tactics
 * rather than playing slowly.
 */
public enum Difficulty {

    /** Sees immediate captures and little else. */
    BEGINNER(2, Duration.ofMillis(200)),

    CASUAL(4, Duration.ofMillis(400)),

    CLUB(6, Duration.ofMillis(800)),

    STRONG(9, Duration.ofSeconds(2)),

    /** As hard as the service will work for one request. */
    MAXIMUM(14, Duration.ofSeconds(5));

    private final int depth;
    private final Duration limit;

    Difficulty(int depth, Duration limit) {
        this.depth = depth;
        this.limit = limit;
    }

    public int depth() {
        return depth;
    }

    public Duration limit() {
        return limit;
    }
}
