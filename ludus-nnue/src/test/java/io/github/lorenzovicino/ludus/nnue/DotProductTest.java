package io.github.lorenzovicino.ludus.nnue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The vectorised inner loop has to give the same answer as the plain one.
 *
 * <p>Not approximately: these are integers, so any difference is a bug rather than rounding. The
 * scalar loop is the reference, which is why it is kept working and tested rather than deleted once
 * the fast path exists.
 *
 * <p>A wrong lane count or a mishandled tail produces a number that is merely a bit off — a plausible
 * evaluation of the wrong position, which would show up as the engine playing slightly worse and
 * nothing else.
 */
class DotProductTest {

    private final DotProduct scalar = new ScalarDotProduct();

    @Test
    void theProcessResolvedToSomething() {
        assertNotNull(DotProduct.best());
        assertNotNull(NnueEvaluator.inferencePath());
        System.out.println("inference path: " + NnueEvaluator.inferencePath());
    }

    @Test
    void theScalarLoopComputesADotProduct() {
        int[] input = {1, 2, 3, 4};
        int[] weights = {0, 0, 10, 20, 30, 40};
        assertEquals(1 * 10 + 2 * 20 + 3 * 30 + 4 * 40, scalar.of(input, weights, 2, 4));
    }

    @Test
    void anEmptyRangeIsZero() {
        assertEquals(0, scalar.of(new int[0], new int[0], 0, 0));
    }

    @Test
    void theVectorisedLoopAgreesWithTheScalarOneExactly() {
        DotProduct vector = vectorisedOrSkip();
        Random random = new Random(20260809L);

        // Every length, not only the layer sizes: a tail handled wrongly is invisible at 512 and
        // wrong at 513, and the next architecture change is what would find it.
        for (int length = 0; length <= 600; length++) {
            int[] input = new int[length];
            int[] weights = new int[length + 32];
            for (int i = 0; i < length; i++) {
                // The ranges inference actually produces: activations up to a wide scale, weights
                // within a byte.
                input[i] = random.nextInt(2049);
                weights[i + 7] = random.nextInt(255) - 127;
            }
            int expected = scalar.of(input, weights, 7, length);
            int actual = vector.of(input, weights, 7, length);
            int checkedLength = length;
            assertEquals(expected, actual, () -> "Disagreement at length " + checkedLength);
        }
    }

    @Test
    void theTwoAgreeOnTheRealLayerShapes() {
        DotProduct vector = vectorisedOrSkip();
        Random random = new Random(7);

        for (int length : new int[] {NnueNetwork.L1_INPUTS, NnueNetwork.L1, NnueNetwork.L2}) {
            int[] input = new int[length];
            int[] weights = new int[length];
            for (int i = 0; i < length; i++) {
                input[i] = random.nextInt(2049);
                weights[i] = random.nextInt(255) - 127;
            }
            assertEquals(scalar.of(input, weights, 0, length), vector.of(input, weights, 0, length));
        }
    }

    @Test
    void negativeProductsSurvive() {
        DotProduct vector = vectorisedOrSkip();
        int[] input = new int[64];
        int[] weights = new int[64];
        java.util.Arrays.fill(input, 2048);
        java.util.Arrays.fill(weights, -127);

        assertEquals(scalar.of(input, weights, 0, 64), vector.of(input, weights, 0, 64));
        assertTrue(vector.of(input, weights, 0, 64) < 0);
    }

    private DotProduct vectorisedOrSkip() {
        DotProduct best = DotProduct.best();
        Assumptions.assumeTrue(best instanceof VectorDotProduct,
                "jdk.incubator.vector is not on the command line, so there is no fast path to check");
        return best;
    }
}
