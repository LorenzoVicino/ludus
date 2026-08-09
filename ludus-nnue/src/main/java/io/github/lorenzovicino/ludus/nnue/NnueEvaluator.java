package io.github.lorenzovicino.ludus.nnue;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Pieces;
import io.github.lorenzovicino.ludus.eval.Evaluator;

/**
 * Act II: the network, behind the interface the search has been calling since M1.
 *
 * <p>Nothing in {@code ludus-search} changes to use this, and nothing in {@code ludus-search} can
 * even refer to it — the module graph makes that a compile error rather than a convention. The
 * composition root picks an implementation and the search never learns which one it got. That was
 * the point of taking the seam on day one, when it was not yet useful for anything.
 *
 * <p>Not thread-safe: it carries an accumulator stack that follows one search down one tree.
 */
public final class NnueEvaluator implements Evaluator {

    private final NnueNetwork network;
    private final Accumulator accumulator;

    /** Both perspectives, clipped, ready for the first dense layer. */
    private final int[] activations = new int[NnueNetwork.L1_INPUTS];
    private final int[] layerOne = new int[NnueNetwork.L1];
    private final int[] layerTwo = new int[NnueNetwork.L2];

    public NnueEvaluator(NnueNetwork network) {
        this.network = network;
        this.accumulator = new Accumulator(network);
    }

    @Override
    public int evaluate(Board board) {
        int us = board.sideToMove();

        // Mover first. This is what makes one network serve both colours: it answers "how is the
        // side to move doing", which is the question the search asks at every node.
        clip(accumulator.perspective(us), 0);
        clip(accumulator.perspective(Pieces.flip(us)), NnueNetwork.HIDDEN);

        propagate(activations, NnueNetwork.L1_INPUTS, network.l1Weights, network.l1Biases, layerOne);
        propagate(layerOne, NnueNetwork.L1, network.l2Weights, network.l2Biases, layerTwo);

        int output = network.outputBias;
        for (int i = 0; i < NnueNetwork.L2; i++) {
            output += layerTwo[i] * network.outputWeights[i];
        }

        // Back out of the fixed-point units the layers accumulate in, and into centipawns. In long,
        // because the product of a wide activation scale and 400 leaves an int with no headroom.
        long scaled = (long) output * NnueNetwork.SCALE;
        long divisor = (long) network.qa() * network.qb();
        return (int) Math.floorDiv(scaled + divisor / 2, divisor);
    }

    /**
     * Clipped ReLU. Saturating at {@link NnueNetwork#QA} is not only the non-linearity — it is what
     * keeps the products in the layers below inside the range their integer types can hold.
     */
    private void clip(short[] source, int offset) {
        int ceiling = network.qa();
        for (int i = 0; i < NnueNetwork.HIDDEN; i++) {
            int value = source[i];
            activations[offset + i] = value < 0 ? 0 : Math.min(value, ceiling);
        }
    }

    private void propagate(int[] input, int inputSize, byte[] weights, int[] biases, int[] output) {
        for (int neuron = 0; neuron < output.length; neuron++) {
            int sum = biases[neuron];
            int base = neuron * inputSize;
            for (int i = 0; i < inputSize; i++) {
                sum += input[i] * weights[base + i];
            }
            output[neuron] = clippedRelu(divideRounding(sum, network.qb()), network.qa());
        }
    }

    /**
     * Divides back out of the weight scale, rounding to nearest rather than truncating.
     *
     * <p>Plain integer division looks harmless here and is not. It always truncates towards zero, so
     * the error is <em>biased</em> rather than random, and it compounds: one unit lost on a first-layer
     * activation re-enters the second layer multiplied by a weight of up to 127 across thirty-two
     * neurons, which is tens of units out of an activation range of 127. Measured against the trained
     * network it was worth up to 29 centipawns — found by the test that compares the two
     * implementations, and invisible to every other test in the project.
     */
    private static int divideRounding(int value, int divisor) {
        return Math.floorDiv(value + divisor / 2, divisor);
    }

    private static int clippedRelu(int value, int ceiling) {
        return value < 0 ? 0 : Math.min(value, ceiling);
    }

    @Override
    public void beforeMakeMove(Board board, int move) {
        accumulator.push(board, move);
    }

    @Override
    public void afterUnmakeMove(Board board, int move) {
        accumulator.pop();
    }

    @Override
    public void reset(Board board) {
        accumulator.reset(board);
    }

    /** Exposed for the invariant test, which is the only thing that should care. */
    Accumulator accumulator() {
        return accumulator;
    }
}
