package io.github.lorenzovicino.ludus.nnue;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * The same loop, several lanes at a time.
 *
 * <p>{@code SPECIES_PREFERRED} asks the JVM for the widest registers this CPU actually has rather
 * than hardcoding a width — eight lanes on AVX2, sixteen on AVX-512, four on a machine with neither,
 * and the same source in every case. That portability is the reason to use the Vector API instead of
 * hand-unrolling: an unrolled loop is tuned for one machine and merely tolerated on the others.
 *
 * <p>This class only loads when {@code jdk.incubator.vector} is on the command line. It is reached
 * through {@link DotProduct#best()}, which falls back rather than failing, so an engine launched
 * without the flag still plays — just slower.
 */
final class VectorDotProduct implements DotProduct {

    private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;

    @Override
    public int of(int[] input, int[] weights, int base, int length) {
        IntVector accumulator = IntVector.zero(SPECIES);
        int lanes = SPECIES.length();
        int bound = SPECIES.loopBound(length);

        int i = 0;
        for (; i < bound; i += lanes) {
            IntVector activations = IntVector.fromArray(SPECIES, input, i);
            IntVector row = IntVector.fromArray(SPECIES, weights, base + i);
            accumulator = activations.mul(row).add(accumulator);
        }

        int sum = accumulator.reduceLanes(VectorOperators.ADD);
        // The layers are powers of two so this rarely runs, but a tail left unhandled would be a
        // silent wrong answer rather than a crash.
        for (; i < length; i++) {
            sum += input[i] * weights[base + i];
        }
        return sum;
    }

    @Override
    public String name() {
        return "vector x" + SPECIES.length();
    }
}
