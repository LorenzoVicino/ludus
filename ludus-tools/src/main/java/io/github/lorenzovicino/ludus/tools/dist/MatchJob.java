package io.github.lorenzovicino.ludus.tools.dist;

import io.github.lorenzovicino.ludus.tools.GamePlayer;

/**
 * One unit of distributable work: an opening, to be played twice with the colours swapped.
 *
 * <p>The pair is the unit rather than the game because the colour swap is what cancels the opening's
 * own bias. Splitting a pair across two workers would still work statistically, but it makes a
 * partial result meaningless if one half is lost — and losing half of a pair is exactly what happens
 * when a worker dies.
 *
 * <h2>Wire format</h2>
 *
 * <p>A pipe-delimited line rather than JSON. These messages are internal to this tool, have four or
 * six fields, and never leave the pair of programs that agree on them; a parser and a serialisation
 * library would be more machinery than the payload deserves. A FEN contains spaces but never a pipe,
 * which is what makes the delimiter safe.
 */
public record MatchJob(int id, String fen) {

    static final String SEPARATOR = "|";

    String encode() {
        return id + SEPARATOR + fen;
    }

    static MatchJob decode(String message) {
        int split = message.indexOf(SEPARATOR);
        if (split < 0) {
            throw new IllegalArgumentException("Malformed job: " + message);
        }
        return new MatchJob(Integer.parseInt(message.substring(0, split)),
                message.substring(split + 1));
    }

    static String encodeResult(MatchJob job, GamePlayer.PairOutcome outcome) {
        return String.join(SEPARATOR,
                String.valueOf(job.id()),
                String.valueOf(outcome.wins()),
                String.valueOf(outcome.draws()),
                String.valueOf(outcome.losses()),
                String.valueOf(outcome.illegalByA()),
                String.valueOf(outcome.illegalByB()),
                job.fen());
    }

    static MatchTransport.Completed decodeResult(String message) {
        String[] fields = message.split("\\|", 7);
        if (fields.length != 7) {
            throw new IllegalArgumentException("Malformed result: " + message);
        }
        MatchJob job = new MatchJob(Integer.parseInt(fields[0]), fields[6]);
        GamePlayer.PairOutcome outcome = new GamePlayer.PairOutcome(
                Integer.parseInt(fields[1]),
                Integer.parseInt(fields[2]),
                Integer.parseInt(fields[3]),
                Integer.parseInt(fields[4]),
                Integer.parseInt(fields[5]));
        return new MatchTransport.Completed(job, outcome);
    }
}
