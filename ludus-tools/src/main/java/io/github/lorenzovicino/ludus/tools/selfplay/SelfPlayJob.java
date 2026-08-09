package io.github.lorenzovicino.ludus.tools.selfplay;

/**
 * A batch of self-play games to generate.
 *
 * <p>A job is many games rather than one because a game at this depth takes a second or two, and a
 * message round trip per game would put more load on the broker than on the search.
 *
 * @param seed  what the openings are derived from, so a job produces the same games wherever it runs
 * @param games how many to play
 * @param depth how deep to search each move
 */
public record SelfPlayJob(int id, long seed, int games, int depth) {

    static final String JOBS_QUEUE = "ludus.selfplay.jobs";
    static final String SAMPLES_QUEUE = "ludus.selfplay.samples";
    static final String JOBS_DEAD_LETTER_QUEUE = "ludus.selfplay.jobs.dlq";

    String encode() {
        return id + "|" + seed + "|" + games + "|" + depth;
    }

    static SelfPlayJob decode(String message) {
        String[] fields = message.split("\\|");
        if (fields.length != 4) {
            throw new IllegalArgumentException("Malformed job: " + message);
        }
        return new SelfPlayJob(Integer.parseInt(fields[0]), Long.parseLong(fields[1]),
                Integer.parseInt(fields[2]), Integer.parseInt(fields[3]));
    }
}
