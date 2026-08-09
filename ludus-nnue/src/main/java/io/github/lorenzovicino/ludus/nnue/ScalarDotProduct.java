package io.github.lorenzovicino.ludus.nnue;

/**
 * The plain loop.
 *
 * <p>Kept working and tested rather than merely present: it is the correctness reference the
 * vectorised path is checked against, and it is what runs when the incubator module is not on the
 * command line — which is every GUI, since they all launch an engine as a bare {@code java -jar}.
 */
final class ScalarDotProduct implements DotProduct {

    @Override
    public int of(int[] input, int[] weights, int base, int length) {
        int sum = 0;
        for (int i = 0; i < length; i++) {
            sum += input[i] * weights[base + i];
        }
        return sum;
    }

    @Override
    public String name() {
        return "scalar";
    }
}
