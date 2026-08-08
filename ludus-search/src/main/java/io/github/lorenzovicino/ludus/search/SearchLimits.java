package io.github.lorenzovicino.ludus.search;

import java.util.concurrent.TimeUnit;

/**
 * What the search is allowed to spend.
 *
 * <p>Two deadlines rather than one, and the distinction matters. The <em>soft</em> limit is checked
 * between iterations: past it, no new iteration starts, because an iteration that cannot finish is
 * wasted work. The <em>hard</em> limit is checked inside the search and abandons it mid-tree, which
 * is the backstop for when an iteration turns out to cost far more than the last one did.
 *
 * @param depth      the deepest iteration to attempt
 * @param softNanos  budget after which no further iteration begins, or {@link #UNLIMITED}
 * @param hardNanos  budget after which the search is abandoned outright, or {@link #UNLIMITED}
 */
public record SearchLimits(int depth, long softNanos, long hardNanos) {

    public static final long UNLIMITED = Long.MAX_VALUE;

    /**
     * Held back from every clock budget. Losing on time forfeits the game outright, which is worse
     * than any move a few extra milliseconds could have found.
     */
    private static final long OVERHEAD_MILLIS = 50;

    /** Assumed remaining moves when the host gives no {@code movestogo}. */
    private static final int DEFAULT_HORIZON = 30;

    public static SearchLimits depth(int depth) {
        return new SearchLimits(depth, UNLIMITED, UNLIMITED);
    }

    public static SearchLimits infinite() {
        return new SearchLimits(Search.MAX_DEPTH, UNLIMITED, UNLIMITED);
    }

    /** A fixed allowance for this move: {@code go movetime}. */
    public static SearchLimits moveTime(long millis) {
        long usable = Math.max(1, millis - OVERHEAD_MILLIS);
        return new SearchLimits(Search.MAX_DEPTH, toNanos(usable), toNanos(usable));
    }

    /**
     * Divides a clock across the rest of the game: {@code go wtime … btime … winc … movestogo …}.
     *
     * <p>The increment is only three quarters counted. Spending all of it every move assumes the
     * move will actually be made at that pace, and a single slow move then eats into the base time
     * with nothing to show for it.
     */
    public static SearchLimits clock(long remainingMillis, long incrementMillis, int movesToGo) {
        int horizon = movesToGo > 0 ? movesToGo : DEFAULT_HORIZON;
        long usable = Math.max(1, remainingMillis - OVERHEAD_MILLIS);

        long target = usable / horizon + incrementMillis * 3 / 4;
        // Never commit most of the clock to one move, however generous the division looks.
        long soft = Math.min(target, usable * 2 / 5);
        long hard = Math.min(soft * 3, usable * 4 / 5);
        return new SearchLimits(Search.MAX_DEPTH, toNanos(soft), toNanos(Math.max(soft, hard)));
    }

    public boolean isTimed() {
        return hardNanos != UNLIMITED;
    }

    private static long toNanos(long millis) {
        return TimeUnit.MILLISECONDS.toNanos(Math.max(1, millis));
    }
}
