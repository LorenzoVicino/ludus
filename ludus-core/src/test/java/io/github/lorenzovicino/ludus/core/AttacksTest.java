package io.github.lorenzovicino.ludus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Validates the magic bitboard tables against ray walking.
 *
 * <p>This is the test that makes searching for the magics at startup safe rather than reckless: a
 * wrong magic produces a plausible attack set for the wrong occupancy, which no downstream test
 * would attribute back to here. Holding every lookup against the definition closes that gap.
 */
class AttacksTest {

    private static final int TRIALS_PER_SQUARE = 1000;
    private static final long SEED = 0x1D6C_A5E5L;

    @Test
    void rookLookupsMatchRayWalking() {
        Random random = new Random(SEED);
        for (int square = 0; square < Squares.COUNT; square++) {
            assertRookMatches(square, Bitboards.EMPTY);
            assertRookMatches(square, Bitboards.ALL);
            for (int trial = 0; trial < TRIALS_PER_SQUARE; trial++) {
                assertRookMatches(square, randomOccupancy(random));
            }
        }
    }

    @Test
    void bishopLookupsMatchRayWalking() {
        Random random = new Random(SEED);
        for (int square = 0; square < Squares.COUNT; square++) {
            assertBishopMatches(square, Bitboards.EMPTY);
            assertBishopMatches(square, Bitboards.ALL);
            for (int trial = 0; trial < TRIALS_PER_SQUARE; trial++) {
                assertBishopMatches(square, randomOccupancy(random));
            }
        }
    }

    @Test
    void queenIsTheUnionOfRookAndBishop() {
        Random random = new Random(SEED);
        for (int square = 0; square < Squares.COUNT; square++) {
            long occupied = randomOccupancy(random);
            assertEquals(Attacks.rook(square, occupied) | Attacks.bishop(square, occupied),
                    Attacks.queen(square, occupied));
        }
    }

    @Test
    void knightAttackCountsAreCorrectAtTheExtremes() {
        assertEquals(2, Bitboards.count(Attacks.knight(Squares.A1)), "A corner knight has two moves");
        assertEquals(2, Bitboards.count(Attacks.knight(Squares.H8)));
        assertEquals(8, Bitboards.count(Attacks.knight(Squares.of(3, 3))), "A central knight has eight");
        assertEquals(3, Bitboards.count(Attacks.knight(Squares.of(1, 0))), "b1 reaches a3, c3 and d2");
    }

    @Test
    void kingAttackCountsAreCorrectAtTheExtremes() {
        assertEquals(3, Bitboards.count(Attacks.king(Squares.A1)));
        assertEquals(5, Bitboards.count(Attacks.king(Squares.E1)));
        assertEquals(8, Bitboards.count(Attacks.king(Squares.of(3, 3))));
    }

    @Test
    void pawnAttacksDoNotWrapAroundTheEdges() {
        // The file masks in the shift are what stop an a-file pawn attacking the h-file one rank
        // down. Getting this wrong produces phantom attackers that only show up in perft.
        assertEquals(Bitboards.bit(Squares.of(1, 2)), Attacks.pawn(Pieces.WHITE, Squares.of(0, 1)),
                "A pawn on a2 attacks b3 and nothing else");
        assertEquals(Bitboards.bit(Squares.of(6, 2)), Attacks.pawn(Pieces.WHITE, Squares.of(7, 1)),
                "A pawn on h2 attacks g3 and nothing else");
        assertEquals(Bitboards.bit(Squares.of(1, 5)), Attacks.pawn(Pieces.BLACK, Squares.of(0, 6)),
                "A pawn on a7 attacks b6 and nothing else");
        assertEquals(Bitboards.bit(Squares.of(6, 5)), Attacks.pawn(Pieces.BLACK, Squares.of(7, 6)),
                "A pawn on h7 attacks g6 and nothing else");
        assertEquals(2, Bitboards.count(Attacks.pawn(Pieces.WHITE, Squares.of(4, 3))),
                "A pawn on e4 attacks two squares");
    }

    private static void assertRookMatches(int square, long occupied) {
        assertEquals(Attacks.referenceRook(square, occupied), Attacks.rook(square, occupied),
                () -> "Rook attacks from " + Squares.name(square) + " disagree with the reference."
                        + "\nOccupancy:\n" + Bitboards.toBoardString(occupied));
    }

    private static void assertBishopMatches(int square, long occupied) {
        assertEquals(Attacks.referenceBishop(square, occupied), Attacks.bishop(square, occupied),
                () -> "Bishop attacks from " + Squares.name(square) + " disagree with the reference."
                        + "\nOccupancy:\n" + Bitboards.toBoardString(occupied));
    }

    /** Roughly a quarter of the board occupied — the density of a real middlegame. */
    private static long randomOccupancy(Random random) {
        return random.nextLong() & random.nextLong();
    }
}
