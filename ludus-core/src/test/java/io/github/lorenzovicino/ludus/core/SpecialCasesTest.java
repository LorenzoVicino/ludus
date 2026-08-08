package io.github.lorenzovicino.ludus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The rules that break move generators, pinned down individually.
 *
 * <p>Perft covers all of this in aggregate, but only as a number. When a count is wrong these
 * tests say which rule broke, which is the difference between a minute and an afternoon.
 * See DESIGN.md §4.3.
 */
class SpecialCasesTest {

    @Test
    void enPassantIsRejectedWhenItExposesOwnKing() {
        // Black king a4, black pawn c4, white pawn d2, white rook h4. The rook's line to the king
        // is blocked by the pawn on c4. After d2-d4, cxd3 en passant would vacate c4 *and* remove
        // the pawn from d4 — two pieces off the fourth rank at once — opening the line.
        //
        // This is the case a directly-legal generator has to reason about explicitly, and the
        // reason stage one makes and unmakes the move instead: playing it out gets the answer for
        // free.
        Board board = Board.fromFen("8/8/8/8/k1p4R/8/3P4/4K3 w - - 0 1");
        board.makeMove(Move.of(Squares.parse("d2"), Squares.parse("d4"), Move.DOUBLE_PUSH));

        assertEquals(Squares.parse("d3"), board.epSquare(), "The double push must offer d3");
        assertTrue(pseudoLegalMoves(board).contains("c4d3"),
                "The capture must be generated pseudo-legally — the legality filter is what rejects it");
        assertFalse(legalMoves(board).contains("c4d3"),
                "cxd3 en passant leaves the king on a4 exposed to the rook on h4");
    }

    @Test
    void enPassantIsAllowedWhenNothingIsExposed() {
        Board board = Board.fromFen("4k3/8/8/8/2p5/8/3P4/4K3 w - - 0 1");
        board.makeMove(Move.of(Squares.parse("d2"), Squares.parse("d4"), Move.DOUBLE_PUSH));
        assertTrue(legalMoves(board).contains("c4d3"), "Nothing is behind the pawn, so the capture stands");
    }

    @Test
    void castlingIsDeniedWhileInCheck() {
        // Black rook on e2 checks along the e-file.
        Board board = Board.fromFen("4k3/8/8/8/8/8/4r3/R3K2R w KQ - 0 1");
        assertTrue(board.inCheck(), "Test position must actually put white in check");

        Set<String> legal = legalMoves(board);
        assertFalse(legal.contains("e1g1"), "Cannot castle out of check");
        assertFalse(legal.contains("e1c1"), "Cannot castle out of check");
    }

    @Test
    void castlingIsDeniedThroughAnAttackedSquare() {
        // Black rook on f2 attacks f1, the square the king crosses going kingside. It does not
        // attack e1, so the king is not in check and the queenside is unaffected.
        Board board = Board.fromFen("4k3/8/8/8/8/8/5r2/R3K2R w KQ - 0 1");
        assertFalse(board.inCheck());

        Set<String> legal = legalMoves(board);
        assertFalse(legal.contains("e1g1"), "The king may not cross the attacked f1");
        assertTrue(legal.contains("e1c1"), "The queenside path is clear and stays legal");
    }

    @Test
    void castlingIsDeniedOntoAnAttackedSquare() {
        // Black rook on g2 attacks g1, the king's destination. Neither e1 nor f1 is attacked, so
        // this one is caught by the legality filter rather than by the path checks.
        Board board = Board.fromFen("4k3/8/8/8/8/8/6r1/R3K2R w KQ - 0 1");
        assertFalse(board.inCheck());
        assertFalse(legalMoves(board).contains("e1g1"), "The king may not land on the attacked g1");
    }

    @Test
    void castlingIsDeniedWhenThePathIsOccupied() {
        Board board = Board.fromFen("4k3/8/8/8/8/8/8/RN2K1BR w KQ - 0 1");
        Set<String> legal = legalMoves(board);
        assertFalse(legal.contains("e1g1"), "The bishop on g1 blocks the kingside");
        assertFalse(legal.contains("e1c1"), "The knight on b1 blocks the queenside");
    }

    @Test
    void bothCastlesAreAvailableFromAClearPosition() {
        Board board = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        Set<String> legal = legalMoves(board);
        assertTrue(legal.contains("e1g1"));
        assertTrue(legal.contains("e1c1"));
    }

    @Test
    void castlingMovesTheRookAndIsReversible() {
        Board board = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        String before = board.stateSignature();

        int shortCastle = Move.of(Squares.E1, Squares.G1, Move.CASTLE_KING);
        board.makeMove(shortCastle);
        assertEquals(Pieces.of(Pieces.WHITE, Pieces.KING), board.pieceAt(Squares.G1));
        assertEquals(Pieces.of(Pieces.WHITE, Pieces.ROOK), board.pieceAt(Squares.F1));
        assertTrue(board.isEmpty(Squares.E1));
        assertTrue(board.isEmpty(Squares.H1));
        assertEquals(Castling.BLACK_KING | Castling.BLACK_QUEEN, board.castlingRights(),
                "Castling spends both of white's rights");
        board.unmakeMove(shortCastle);
        assertEquals(before, board.stateSignature());

        int longCastle = Move.of(Squares.E1, Squares.C1, Move.CASTLE_QUEEN);
        board.makeMove(longCastle);
        assertEquals(Pieces.of(Pieces.WHITE, Pieces.KING), board.pieceAt(Squares.C1));
        assertEquals(Pieces.of(Pieces.WHITE, Pieces.ROOK), board.pieceAt(Squares.D1));
        assertTrue(board.isEmpty(Squares.A1));
        board.unmakeMove(longCastle);
        assertEquals(before, board.stateSignature());
    }

    @Test
    void capturingARookOnItsHomeSquareRemovesTheCastlingRight() {
        // Rxh8 touches exactly two squares, and each one costs a right: h1 because white's rook
        // left it, h8 because black's rook was taken there. Losing the right on capture is why the
        // castling mask is applied to the destination as well as the origin — a mask on the origin
        // alone would leave black claiming a kingside castle with no rook to do it with.
        //
        // The two queenside rights survive, and that matters as much: nothing touched a1, a8 or
        // either king, so a mask that cleared them would be over-eager.
        Board board = Board.fromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        board.makeMove(Move.of(Squares.H1, Squares.H8, Move.CAPTURE));
        assertEquals(Castling.WHITE_QUEEN | Castling.BLACK_QUEEN, board.castlingRights(),
                "Both kingside rights go — white's with the departing rook, black's with the captured"
                        + " one — while both queenside rights are untouched");
    }

    @Test
    void promotionGeneratesAllFourPieces() {
        Board board = Board.fromFen("8/P7/8/8/8/8/8/K6k w - - 0 1");
        assertTrue(legalMoves(board).containsAll(Set.of("a7a8q", "a7a8r", "a7a8b", "a7a8n")),
                "Underpromotion matters: a rook or knight can avoid handing over a stalemate");
    }

    @Test
    void capturingPromotionGeneratesAllFourPieces() {
        Board board = Board.fromFen("1n6/P7/8/8/8/8/8/K6k w - - 0 1");
        assertTrue(legalMoves(board).containsAll(Set.of("a7b8q", "a7b8r", "a7b8b", "a7b8n")));
    }

    @Test
    void promotionReplacesThePawnAndIsReversible() {
        Board board = Board.fromFen("1n6/P7/8/8/8/8/8/K6k w - - 0 1");
        String before = board.stateSignature();

        int move = Move.of(Squares.parse("a7"), Squares.B8, Move.PROMO_CAPTURE_KNIGHT);
        board.makeMove(move);
        assertEquals(Pieces.of(Pieces.WHITE, Pieces.KNIGHT), board.pieceAt(Squares.B8));
        assertEquals(0, Bitboards.count(board.pieces(Pieces.WHITE, Pieces.PAWN)), "The pawn is gone");
        assertEquals(0, Bitboards.count(board.pieces(Pieces.BLACK, Pieces.KNIGHT)), "The black knight was taken");
        board.unmakeMove(move);
        assertEquals(before, board.stateSignature());
    }

    @Test
    void doublePushOffersEnPassantOnlyAfterADoublePush() {
        Board board = Board.startPosition();
        board.makeMove(Move.of(Squares.parse("e2"), Squares.parse("e4"), Move.DOUBLE_PUSH));
        assertEquals(Squares.parse("e3"), board.epSquare());

        board.makeMove(Move.of(Squares.parse("e7"), Squares.parse("e6"), Move.QUIET));
        assertEquals(Squares.NONE, board.epSquare(), "A single push offers nothing");
    }

    @Test
    void halfmoveClockResetsOnPawnMovesAndCaptures() {
        Board board = Board.fromFen("4k3/8/8/8/8/5n2/8/4K1N1 w - - 7 20");
        board.makeMove(Move.of(Squares.G1, Squares.parse("h3"), Move.QUIET));
        assertEquals(8, board.halfmoveClock(), "A quiet knight move advances the clock");

        board.makeMove(Move.of(Squares.parse("f3"), Squares.parse("h2"), Move.QUIET));
        assertEquals(9, board.halfmoveClock());
    }

    private static Set<String> legalMoves(Board board) {
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));
        return toUci(moves, count);
    }

    private static Set<String> pseudoLegalMoves(Board board) {
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        return toUci(moves, MoveGenerator.generate(board, moves));
    }

    private static Set<String> toUci(int[] moves, int count) {
        Set<String> uci = new TreeSet<>();
        for (int i = 0; i < count; i++) {
            uci.add(Move.toUci(moves[i]));
        }
        return uci;
    }
}
