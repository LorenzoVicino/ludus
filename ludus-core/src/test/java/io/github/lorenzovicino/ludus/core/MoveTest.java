package io.github.lorenzovicino.ludus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MoveTest {

    @Test
    void everyEncodingRoundTrips() {
        for (int from = 0; from < Squares.COUNT; from++) {
            for (int to = 0; to < Squares.COUNT; to++) {
                for (int flags = 0; flags < 16; flags++) {
                    int move = Move.of(from, to, flags);
                    assertEquals(from, Move.from(move));
                    assertEquals(to, Move.to(move));
                    assertEquals(flags, Move.flags(move));
                }
            }
        }
    }

    @Test
    void captureBitCoversOrdinaryEnPassantAndPromotionCaptures() {
        assertTrue(Move.isCapture(Move.of(Squares.E1, Squares.parse("e2"), Move.CAPTURE)));
        assertTrue(Move.isCapture(Move.of(Squares.E1, Squares.parse("e2"), Move.EP_CAPTURE)));
        assertTrue(Move.isCapture(Move.of(Squares.E1, Squares.parse("e2"), Move.PROMO_CAPTURE_QUEEN)));
        assertTrue(Move.isCapture(Move.of(Squares.E1, Squares.parse("e2"), Move.PROMO_CAPTURE_KNIGHT)));

        assertFalse(Move.isCapture(Move.of(Squares.E1, Squares.parse("e2"), Move.QUIET)));
        assertFalse(Move.isCapture(Move.of(Squares.E1, Squares.parse("e2"), Move.DOUBLE_PUSH)));
        assertFalse(Move.isCapture(Move.of(Squares.E1, Squares.parse("e2"), Move.CASTLE_KING)));
        assertFalse(Move.isCapture(Move.of(Squares.E1, Squares.parse("e2"), Move.CASTLE_QUEEN)));
        assertFalse(Move.isCapture(Move.of(Squares.E1, Squares.parse("e2"), Move.PROMO_QUEEN)));
    }

    @Test
    void promotionBitAndTypeAgreeForAllEightPromotionFlags() {
        assertEquals(Pieces.KNIGHT, Move.promotionType(Move.of(0, 0, Move.PROMO_KNIGHT)));
        assertEquals(Pieces.BISHOP, Move.promotionType(Move.of(0, 0, Move.PROMO_BISHOP)));
        assertEquals(Pieces.ROOK, Move.promotionType(Move.of(0, 0, Move.PROMO_ROOK)));
        assertEquals(Pieces.QUEEN, Move.promotionType(Move.of(0, 0, Move.PROMO_QUEEN)));
        assertEquals(Pieces.KNIGHT, Move.promotionType(Move.of(0, 0, Move.PROMO_CAPTURE_KNIGHT)));
        assertEquals(Pieces.BISHOP, Move.promotionType(Move.of(0, 0, Move.PROMO_CAPTURE_BISHOP)));
        assertEquals(Pieces.ROOK, Move.promotionType(Move.of(0, 0, Move.PROMO_CAPTURE_ROOK)));
        assertEquals(Pieces.QUEEN, Move.promotionType(Move.of(0, 0, Move.PROMO_CAPTURE_QUEEN)));

        for (int flags = 0; flags < 16; flags++) {
            assertEquals(flags >= 8, Move.isPromotion(Move.of(0, 0, flags)),
                    "Flag " + flags + " promotion classification");
        }
    }

    @Test
    void castleAndDoublePushClassification() {
        assertTrue(Move.isCastle(Move.of(Squares.E1, Squares.G1, Move.CASTLE_KING)));
        assertTrue(Move.isCastle(Move.of(Squares.E1, Squares.C1, Move.CASTLE_QUEEN)));
        assertFalse(Move.isCastle(Move.of(Squares.E1, Squares.G1, Move.QUIET)));
        assertTrue(Move.isDoublePush(Move.of(Squares.E1, Squares.E1 + 16, Move.DOUBLE_PUSH)));
        assertTrue(Move.isEnPassant(Move.of(Squares.E1, Squares.E1, Move.EP_CAPTURE)));
    }

    @Test
    void uciNotation() {
        assertEquals("e2e4", Move.toUci(Move.of(Squares.parse("e2"), Squares.parse("e4"), Move.DOUBLE_PUSH)));
        assertEquals("e1g1", Move.toUci(Move.of(Squares.E1, Squares.G1, Move.CASTLE_KING)));
        assertEquals("a7a8q", Move.toUci(Move.of(Squares.parse("a7"), Squares.A8, Move.PROMO_QUEEN)));
        assertEquals("b7c8n", Move.toUci(Move.of(Squares.parse("b7"), Squares.C8, Move.PROMO_CAPTURE_KNIGHT)));
    }

    @Test
    void squareNamesAndParsingAgree() {
        for (int square = 0; square < Squares.COUNT; square++) {
            assertEquals(square, Squares.parse(Squares.name(square)));
        }
        assertEquals("a1", Squares.name(Squares.A1));
        assertEquals("h8", Squares.name(Squares.H8));
        assertEquals("e1", Squares.name(Squares.E1));
        assertEquals("-", Squares.name(Squares.NONE));
        assertEquals(Squares.NONE, Squares.parse("-"));
    }
}
