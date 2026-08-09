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

        // Back out of the fixed-point units the layers accumulate in, and into centipawns.
        return output * NnueNetwork.SCALE / (NnueNetwork.QA * NnueNetwork.QB);
    }

    /**
     * Clipped ReLU. Saturating at {@link NnueNetwork#QA} is not only the non-linearity — it is what
     * keeps the products in the layers below inside the range their integer types can hold.
     */
    private void clip(short[] source, int offset) {
        for (int i = 0; i < NnueNetwork.HIDDEN; i++) {
            int value = source[i];
            activations[offset + i] = value < 0 ? 0 : Math.min(value, NnueNetwork.QA);
        }
    }

    private static void propagate(int[] input, int inputSize, byte[] weights, int[] biases,
                                  int[] output) {
        for (int neuron = 0; neuron < output.length; neuron++) {
            int sum = biases[neuron];
            int base = neuron * inputSize;
            for (int i = 0; i < inputSize; i++) {
                sum += input[i] * weights[base + i];
            }
            // Divide out the weight scale so the result is back in activation units, then clip.
            int scaled = sum / NnueNetwork.QB;
            output[neuron] = scaled < 0 ? 0 : Math.min(scaled, NnueNetwork.QA);
        }
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
