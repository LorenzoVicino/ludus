package io.github.lorenzovicino.ludus.tools.selfplay;

/**
 * One labelled position: what the network will be trained on.
 *
 * <p>Both labels are from the point of view of <em>the side to move</em>, matching what the evaluation
 * returns and what the network is asked to predict. Mixing the two conventions is a mistake that
 * trains perfectly well and produces a network convinced one colour is winning.
 *
 * @param fen    the position
 * @param score  centipawns from a shallow search, the tactical half of the label
 * @param result how the game ended, the strategic half: {@link #WIN}, {@link #DRAW} or {@link #LOSS}
 */
public record SelfPlaySample(String fen, int score, int result) {

    public static final int LOSS = 0;
    public static final int DRAW = 1;
    public static final int WIN = 2;

    private static final String SEPARATOR = "|";

    /**
     * The result as a small integer rather than a fraction, deliberately: writing 0.5 through a
     * default formatter on an Italian locale produces "0,5", and a training file full of commas is
     * a bug discovered days later in Python.
     */
    public String encode() {
        return fen + SEPARATOR + score + SEPARATOR + result;
    }

    public static SelfPlaySample decode(String line) {
        String[] fields = line.split("\\|");
        if (fields.length != 3) {
            throw new IllegalArgumentException("Malformed sample: " + line);
        }
        return new SelfPlaySample(fields[0], Integer.parseInt(fields[1]),
                Integer.parseInt(fields[2]));
    }

    /** The same sample seen from the other side, for checking symmetry. */
    public SelfPlaySample flipped(String flippedFen) {
        return new SelfPlaySample(flippedFen, -score, WIN - result);
    }
}
