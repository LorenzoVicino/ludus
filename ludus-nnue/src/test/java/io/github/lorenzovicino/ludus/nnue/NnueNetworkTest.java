package io.github.lorenzovicino.ludus.nnue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Pieces;
import io.github.lorenzovicino.ludus.core.Squares;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NnueNetworkTest {

    private final NnueNetwork network = NnueNetwork.random(20260809L);

    @Test
    void aNetworkSurvivesBeingWrittenAndReadBack() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        network.store(out);
        NnueNetwork restored = NnueNetwork.load(new ByteArrayInputStream(out.toByteArray()));

        assertArrayEquals(network.featureWeights, restored.featureWeights);
        assertArrayEquals(network.featureBiases, restored.featureBiases);
        assertArrayEquals(network.l1Weights, restored.l1Weights);
        assertArrayEquals(network.l1Biases, restored.l1Biases);
        assertArrayEquals(network.l2Weights, restored.l2Weights);
        assertArrayEquals(network.l2Biases, restored.l2Biases);
        assertArrayEquals(network.outputWeights, restored.outputWeights);
        assertEquals(network.outputBias, restored.outputBias);
    }

    @Test
    void somethingThatIsNotANetworkIsRefused() {
        byte[] rubbish = "this is not a network at all, not even close".getBytes();
        assertThrows(IOException.class, () -> NnueNetwork.load(new ByteArrayInputStream(rubbish)));
    }

    @Test
    void aPieceIsTheFirstHalfOfItsOwnersFeatures() {
        // The first 384 inputs always mean "mine". Getting this wrong swaps the meaning of half the
        // network for one colour.
        int whitePawnE2 = NnueNetwork.featureIndex(
                Pieces.WHITE, Pieces.WHITE, Pieces.PAWN, Squares.parse("e2"));
        int blackPawnE2ForWhite = NnueNetwork.featureIndex(
                Pieces.WHITE, Pieces.BLACK, Pieces.PAWN, Squares.parse("e2"));

        assertTrue(whitePawnE2 < 384, "Own pieces occupy the first half");
        assertTrue(blackPawnE2ForWhite >= 384, "Theirs occupy the second");
    }

    @Test
    void theBlackPerspectiveMirrorsTheBoard() {
        // A black pawn on the seventh rank has to look to black exactly as a white pawn on the
        // second rank looks to white. Swapping the colours without mirroring the square, or the
        // reverse, produces a network that plays badly for one side only.
        int whitePawnE2 = NnueNetwork.featureIndex(
                Pieces.WHITE, Pieces.WHITE, Pieces.PAWN, Squares.parse("e2"));
        int blackPawnE7 = NnueNetwork.featureIndex(
                Pieces.BLACK, Pieces.BLACK, Pieces.PAWN, Squares.parse("e7"));

        assertEquals(whitePawnE2, blackPawnE7);
    }

    @Test
    void everyFeatureIndexIsInRangeAndDistinct() {
        boolean[] seen = new boolean[NnueNetwork.INPUTS];
        for (int color = 0; color < Pieces.COLOR_COUNT; color++) {
            for (int type = 0; type < Pieces.TYPE_COUNT; type++) {
                for (int square = 0; square < Squares.COUNT; square++) {
                    int index = NnueNetwork.featureIndex(Pieces.WHITE, color, type, square);
                    assertTrue(index >= 0 && index < NnueNetwork.INPUTS,
                            () -> "Index out of range: " + index);
                    assertTrue(!seen[index], () -> "Two pieces share feature " + index);
                    seen[index] = true;
                }
            }
        }
    }

    /**
     * The architecture guarantees this exactly, even untrained: mirroring a position and swapping the
     * colours must produce the same score, because the mover's perspective is computed from the same
     * feature indices either way and is concatenated first in both.
     *
     * <p>Which makes it a sharp test of two things at once — the feature indexing and the
     * mover-first ordering — that needs no trained weights to be meaningful.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1",
    })
    void theScoreIsTheSameFromEitherSideOfAMirroredPosition(String fen) {
        NnueEvaluator evaluator = new NnueEvaluator(network);

        Board original = Board.fromFen(fen);
        evaluator.reset(original);
        int first = evaluator.evaluate(original);

        Board mirrored = Board.fromFen(mirror(fen));
        evaluator.reset(mirrored);
        int second = evaluator.evaluate(mirrored);

        assertEquals(first, second,
                () -> "Not colour-symmetric.\n  " + fen + "\n  " + mirror(fen));
    }

    @Test
    void scoresLandInAPlausibleRange() {
        // Random weights say nothing about chess, but they must not produce nonsense either: an
        // overflow or a misplaced scale shows up here as a score in the millions.
        NnueEvaluator evaluator = new NnueEvaluator(network);
        Board board = Board.startPosition();
        evaluator.reset(board);

        int score = evaluator.evaluate(board);
        assertTrue(Math.abs(score) < 20_000,
                () -> "A score of " + score + " means an overflow or a wrong scale, not an opinion");
    }

    @Test
    void differentSeedsGiveDifferentNetworks() {
        assertNotEquals(NnueNetwork.random(1).outputBias + NnueNetwork.random(1).l1Biases[0],
                NnueNetwork.random(2).outputBias + NnueNetwork.random(2).l1Biases[0]);
    }

    /** Flips the board about the horizontal axis and swaps the colours. */
    private static String mirror(String fen) {
        String[] fields = fen.trim().split("\\s+");
        String[] rows = fields[0].split("/");

        StringBuilder placement = new StringBuilder();
        for (int i = rows.length - 1; i >= 0; i--) {
            if (placement.length() > 0) {
                placement.append('/');
            }
            placement.append(swapCase(rows[i]));
        }

        String side = fields[1].equals("w") ? "b" : "w";
        String castling = swapCase(fields[2]);
        String ep = fields[3].equals("-")
                ? "-"
                : "" + fields[3].charAt(0) + (char) ('1' + '8' - fields[3].charAt(1));
        return placement + " " + side + " " + castling + " " + ep + " 0 1";
    }

    private static String swapCase(String text) {
        StringBuilder swapped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            swapped.append(Character.isUpperCase(c) ? Character.toLowerCase(c)
                    : Character.isLowerCase(c) ? Character.toUpperCase(c) : c);
        }
        return swapped.toString();
    }
}
