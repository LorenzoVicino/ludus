package io.github.lorenzovicino.ludus.search;

import java.util.Arrays;

/**
 * A hash table of positions already searched, so a transposition reached by a different move order
 * is answered instead of re-searched.
 *
 * <h2>Two arrays of primitives, not an array of entries</h2>
 *
 * <p>The obvious Java design — {@code TTEntry[]} — is the wrong one, and the reason is a good
 * illustration of what the JVM makes you think about. An array of objects stores references: every
 * probe follows a pointer to a separate heap object, so it costs two cache misses instead of one,
 * and each entry carries a 16-byte header on top of its actual contents. On a 256 MB table, where
 * essentially every probe misses cache, that doubles the cost of the single hottest lookup in the
 * engine. Two parallel {@code long[]} keep key and payload adjacent and add nothing per entry.
 *
 * <p>In C++ a {@code struct} array would have given this for free. Here it has to be chosen.
 *
 * <h2>Mate scores are stored relative to the node</h2>
 *
 * <p>A mate score carries its distance, and the search measures that distance from the root. An
 * entry written at ply 8 and read at ply 4 would then claim a mate four plies closer than it is,
 * which makes the engine announce mates that do not exist. The distance is converted to be relative
 * to the entry's own node on the way in and back on the way out.
 */
public final class TranspositionTable {

    public static final int BOUND_NONE = 0;
    /** The score is exact: the search neither failed high nor low. */
    public static final int BOUND_EXACT = 1;
    /** The true score is at least this: a beta cutoff. */
    public static final int BOUND_LOWER = 2;
    /** The true score is at most this: nothing beat alpha. */
    public static final int BOUND_UPPER = 3;

    private static final int DEFAULT_MEGABYTES = 64;
    private static final int MIN_ENTRIES = 1024;
    /** 2^26 entries is 1 GB, which is the most this accepts. */
    private static final int MAX_ENTRIES = 1 << 26;
    private static final int AGE_LIMIT = 1 << 6;

    private long[] keys;
    private long[] data;
    private int mask;
    private int age;

    public TranspositionTable() {
        this(DEFAULT_MEGABYTES);
    }

    public TranspositionTable(int megabytes) {
        resize(megabytes);
    }

    /** Rebuilds the table at a new size, discarding everything in it. */
    public void resize(int megabytes) {
        long bytesPerEntry = 2L * Long.BYTES;
        long wanted = Math.max(1L, megabytes) * 1024L * 1024L / bytesPerEntry;
        long clamped = Math.max(MIN_ENTRIES, Math.min(wanted, MAX_ENTRIES));
        int capacity = Integer.highestOneBit((int) clamped);

        keys = new long[capacity];
        data = new long[capacity];
        mask = capacity - 1;
        age = 0;
    }

    public void clear() {
        Arrays.fill(keys, 0L);
        Arrays.fill(data, 0L);
        age = 0;
    }

    /** Marks the start of a new search so entries from earlier ones become replaceable. */
    public void newSearch() {
        age = (age + 1) % AGE_LIMIT;
    }

    public int capacity() {
        return keys.length;
    }

    /** @return the packed entry for {@code key}, or 0 when there is none. */
    public long probe(long key) {
        int index = (int) (key & mask);
        return keys[index] == key ? data[index] : 0L;
    }

    public void store(long key, int move, int score, int depth, int bound, int ply) {
        int index = (int) (key & mask);
        long existing = data[index];

        // Keep the deeper result, but never let an entry from an earlier search hold a slot: it
        // describes a position the game has probably left behind.
        //
        // Note what is deliberately *not* here: a clause letting a matching key always win. It is
        // the obvious thing to write and it is wrong — a depth-2 result for a position already
        // solved to depth 10 would evict the better answer and the search would redo the work it had
        // paid for. Depth decides, whatever the key says.
        boolean replace = existing == 0
                || ageOf(existing) != age
                || depth >= depthOf(existing);
        if (!replace) {
            return;
        }

        keys[index] = key;
        data[index] = pack(move, toTableScore(score, ply), depth, bound, age);
    }

    public static int moveOf(long entry) {
        return (int) (entry & 0xFFFFL);
    }

    /** The stored score converted back to this search's distance-from-root convention. */
    public static int scoreOf(long entry, int ply) {
        return fromTableScore((short) ((entry >>> 16) & 0xFFFFL), ply);
    }

    public static int depthOf(long entry) {
        return (int) ((entry >>> 32) & 0xFFL);
    }

    public static int boundOf(long entry) {
        return (int) ((entry >>> 40) & 0x3L);
    }

    private static int ageOf(long entry) {
        return (int) ((entry >>> 42) & 0x3FL);
    }

    private static long pack(int move, int score, int depth, int bound, int age) {
        return (move & 0xFFFFL)
                | ((score & 0xFFFFL) << 16)
                | ((long) (depth & 0xFF) << 32)
                | ((long) (bound & 0x3) << 40)
                | ((long) (age & 0x3F) << 42);
    }

    static int toTableScore(int score, int ply) {
        if (score >= Search.MATE_THRESHOLD) {
            return score + ply;
        }
        if (score <= -Search.MATE_THRESHOLD) {
            return score - ply;
        }
        return score;
    }

    static int fromTableScore(int score, int ply) {
        if (score >= Search.MATE_THRESHOLD) {
            return score - ply;
        }
        if (score <= -Search.MATE_THRESHOLD) {
            return score + ply;
        }
        return score;
    }
}
