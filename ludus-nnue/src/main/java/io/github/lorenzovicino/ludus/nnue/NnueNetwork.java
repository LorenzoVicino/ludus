package io.github.lorenzovicino.ludus.nnue;

import io.github.lorenzovicino.ludus.core.Pieces;
import io.github.lorenzovicino.ludus.core.Squares;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * The quantised weights of the evaluation network.
 *
 * <pre>
 * 768 features (64 squares x 6 piece types x 2 colours), sparse
 *    |
 *    +-- own perspective    --&gt; feature transformer  768 -&gt; 256
 *    +-- their perspective  --&gt; (the same weights)   768 -&gt; 256
 *                                     |
 *                     concatenated, mover first  -&gt; 512
 *                                     |
 *                               clipped ReLU
 *                                     |
 *                              512 -&gt; 32 -&gt; 32 -&gt; 1
 * </pre>
 *
 * <h2>Why the perspectives are concatenated mover-first</h2>
 *
 * <p>It is what makes the network symmetric. Fed "own pieces then theirs" it learns "the side to move
 * is better off", which is the same question the search asks at every node. Fed white-then-black it
 * would learn about white, and every score would need flipping by hand.
 *
 * <h2>Quantisation</h2>
 *
 * <p>Inference runs on integers. That is what makes it fast enough to sit inside a search, and it
 * introduces a controlled discrepancy against the float network that was trained — which is exactly
 * what the Java-against-PyTorch test measures.
 *
 * <ul>
 *   <li>Feature transformer weights and the accumulator: {@code short}. The activation range
 *       {@link #QA} is the unit, so a trained weight {@code w} is stored as {@code round(w * QA)}.</li>
 *   <li>Clipped ReLU saturates the accumulator to {@code [0, QA]}, which is what keeps the products
 *       below in range.</li>
 *   <li>Hidden layer weights: {@code byte}, scaled by {@link #QB}, accumulating into {@code int}.</li>
 *   <li>The output is divided back by {@code QA * QB} and multiplied by {@link #SCALE} to land in
 *       centipawns.</li>
 * </ul>
 */
public final class NnueNetwork {

    /** Squares times piece types times colours. */
    public static final int INPUTS = 768;
    public static final int HIDDEN = 256;
    /** Both perspectives, concatenated. */
    public static final int L1_INPUTS = HIDDEN * 2;
    public static final int L1 = 32;
    public static final int L2 = 32;

    /** Activation range: the clipped ReLU saturates here, and feature weights are scaled by it. */
    public static final int QA = 127;
    /** Scale for the dense layers, kept a power of two so the division is a shift. */
    public static final int QB = 64;
    /** Turns the network's own units into centipawns. */
    public static final int SCALE = 400;

    private static final int MAGIC = 0x4C55_444E; // "LUDN"
    private static final int FORMAT_VERSION = 1;

    final short[] featureWeights;  // [INPUTS * HIDDEN], column-major by feature
    final short[] featureBiases;   // [HIDDEN]
    final byte[] l1Weights;        // [L1 * L1_INPUTS]
    final int[] l1Biases;          // [L1]
    final byte[] l2Weights;        // [L2 * L1]
    final int[] l2Biases;          // [L2]
    final byte[] outputWeights;    // [L2]
    final int outputBias;

    NnueNetwork(short[] featureWeights, short[] featureBiases, byte[] l1Weights, int[] l1Biases,
                byte[] l2Weights, int[] l2Biases, byte[] outputWeights, int outputBias) {
        this.featureWeights = featureWeights;
        this.featureBiases = featureBiases;
        this.l1Weights = l1Weights;
        this.l1Biases = l1Biases;
        this.l2Weights = l2Weights;
        this.l2Biases = l2Biases;
        this.outputWeights = outputWeights;
        this.outputBias = outputBias;
    }

    /**
     * The index of a piece's feature, seen from {@code perspective}.
     *
     * <p>Two things happen for the black perspective, and both are needed: the colours swap, so the
     * first half of the inputs always means "my pieces", and the square is mirrored vertically, so a
     * pawn on the seventh rank looks to black exactly as a pawn on the second rank looks to white.
     * Doing one without the other produces a network that trains and plays badly for one colour.
     */
    public static int featureIndex(int perspective, int color, int type, int square) {
        boolean own = color == perspective;
        int orientedSquare = perspective == Pieces.WHITE ? square : square ^ 56;
        return (own ? 0 : 1) * 384 + type * 64 + orientedSquare;
    }

    /** Reads a network written by the training exporter. */
    public static NnueNetwork load(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return load(in);
        }
    }

    public static NnueNetwork load(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);

        if (in.readInt() != MAGIC) {
            throw new IOException("Not a ludus network file");
        }
        int version = in.readInt();
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported network version " + version);
        }
        expect(in.readInt(), INPUTS, "input size");
        expect(in.readInt(), HIDDEN, "hidden size");
        expect(in.readInt(), L1, "first layer size");
        expect(in.readInt(), L2, "second layer size");

        short[] featureWeights = readShorts(in, INPUTS * HIDDEN);
        short[] featureBiases = readShorts(in, HIDDEN);
        byte[] l1Weights = readBytes(in, L1 * L1_INPUTS);
        int[] l1Biases = readInts(in, L1);
        byte[] l2Weights = readBytes(in, L2 * L1);
        int[] l2Biases = readInts(in, L2);
        byte[] outputWeights = readBytes(in, L2);
        int outputBias = in.readInt();

        return new NnueNetwork(featureWeights, featureBiases, l1Weights, l1Biases,
                l2Weights, l2Biases, outputWeights, outputBias);
    }

    public void store(OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(MAGIC);
        out.writeInt(FORMAT_VERSION);
        out.writeInt(INPUTS);
        out.writeInt(HIDDEN);
        out.writeInt(L1);
        out.writeInt(L2);

        for (short value : featureWeights) {
            out.writeShort(value);
        }
        for (short value : featureBiases) {
            out.writeShort(value);
        }
        out.write(l1Weights);
        for (int value : l1Biases) {
            out.writeInt(value);
        }
        out.write(l2Weights);
        for (int value : l2Biases) {
            out.writeInt(value);
        }
        out.write(outputWeights);
        out.writeInt(outputBias);
        out.flush();
    }

    /**
     * A network of small random weights, for tests.
     *
     * <p>The accumulator invariant — that the incremental update matches a full recomputation bit for
     * bit — is a property of the update arithmetic, not of what the weights mean. Random weights test
     * it exactly as well as trained ones, and they are available now rather than after a training
     * run.
     */
    public static NnueNetwork random(long seed) {
        Random random = new Random(seed);

        short[] featureWeights = new short[INPUTS * HIDDEN];
        for (int i = 0; i < featureWeights.length; i++) {
            // Kept small so a realistic number of active features cannot overflow the accumulator.
            featureWeights[i] = (short) (random.nextInt(41) - 20);
        }
        short[] featureBiases = new short[HIDDEN];
        for (int i = 0; i < featureBiases.length; i++) {
            featureBiases[i] = (short) (random.nextInt(41) - 20);
        }

        byte[] l1Weights = randomBytes(random, L1 * L1_INPUTS);
        int[] l1Biases = randomInts(random, L1);
        byte[] l2Weights = randomBytes(random, L2 * L1);
        int[] l2Biases = randomInts(random, L2);
        byte[] outputWeights = randomBytes(random, L2);

        return new NnueNetwork(featureWeights, featureBiases, l1Weights, l1Biases,
                l2Weights, l2Biases, outputWeights, random.nextInt(2001) - 1000);
    }

    private static byte[] randomBytes(Random random, int count) {
        byte[] values = new byte[count];
        for (int i = 0; i < count; i++) {
            values[i] = (byte) (random.nextInt(63) - 31);
        }
        return values;
    }

    private static int[] randomInts(Random random, int count) {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = random.nextInt(2001) - 1000;
        }
        return values;
    }

    private static void expect(int actual, int expected, String what) throws IOException {
        if (actual != expected) {
            throw new IOException("Network " + what + " is " + actual + ", this build expects " + expected);
        }
    }

    private static short[] readShorts(DataInputStream in, int count) throws IOException {
        short[] values = new short[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readShort();
        }
        return values;
    }

    private static byte[] readBytes(DataInputStream in, int count) throws IOException {
        byte[] values = new byte[count];
        in.readFully(values);
        return values;
    }

    private static int[] readInts(DataInputStream in, int count) throws IOException {
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readInt();
        }
        return values;
    }

    static {
        // A silent mismatch here would misindex every feature on the board.
        if (INPUTS != Pieces.COLOR_COUNT * Pieces.TYPE_COUNT * Squares.COUNT) {
            throw new AssertionError("Feature count does not match the board");
        }
    }
}
