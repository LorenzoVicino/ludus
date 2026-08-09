package io.github.lorenzovicino.ludus.nnue;

/**
 * The inner loop of the dense layers, which is where nearly all the evaluation time goes.
 *
 * <p>Seventeen thousand multiply-accumulates run at every leaf of the search, so this one method is
 * the difference between a network the engine can afford and one it cannot. Measured: the first
 * version cost nineteen times the hand-crafted evaluation, which is two to three plies of depth given
 * away before the network's opinion counts for anything.
 *
 * <p>An interface with two implementations, chosen once at class initialisation. Only one is ever
 * loaded in a run, so the call site stays monomorphic and the JIT inlines it — the same argument that
 * makes the {@code Evaluator} seam free.
 */
interface DotProduct {

    /** @return {@code sum(input[i] * weights[base + i])} for {@code i} in {@code [0, length)} */
    int of(int[] input, int[] weights, int base, int length);

    /** A short word for the benchmark and the {@code info string} line to report. */
    String name();

    /**
     * The vectorised path when the incubator module is present, the scalar one otherwise.
     *
     * <p>{@code jdk.incubator.vector} has to be requested with {@code --add-modules} on the command
     * line, and a GUI launches an engine as a bare {@code java -jar}. So the fast path cannot be
     * assumed: loading it is attempted, and a failure means the class was compiled against a module
     * that is not resolvable here, which is a configuration fact rather than an error.
     */
    static DotProduct best() {
        try {
            Class<?> vectorised = Class.forName(
                    "io.github.lorenzovicino.ludus.nnue.VectorDotProduct");
            return (DotProduct) vectorised.getDeclaredConstructor().newInstance();
        } catch (Throwable absent) {
            return new ScalarDotProduct();
        }
    }
}
