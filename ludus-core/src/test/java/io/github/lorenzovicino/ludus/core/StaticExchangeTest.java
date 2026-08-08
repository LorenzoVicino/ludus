package io.github.lorenzovicino.ludus.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StaticExchangeTest {

    private final StaticExchange exchange = new StaticExchange();

    @Test
    void anUndefendedQueenIsWorthAQueen() {
        // Rook h1 takes the queen on h5 with an empty file between and nothing to recapture.
        Board board = Board.fromFen("4k3/8/8/7q/8/8/8/K6R w - - 0 1");
        assertEquals(950, see(board, "h1h5"));
    }

    @Test
    void pawnForPawnIsAnEvenTrade() {
        // exd5 cxd5 — a pawn each way.
        Board board = Board.fromFen("4k3/8/2p5/3p4/4P3/8/8/4K3 w - - 0 1");
        assertEquals(0, see(board, "e4d5"));
    }

    @Test
    void aQueenTakingADefendedPawnLosesAQueen() {
        // This is the case a victim-minus-attacker comparison gets exactly backwards: it reads
        // "+100, win a pawn", when the position is "-850, lose a queen for a pawn".
        Board board = Board.fromFen("4k3/8/2p5/3p4/8/8/8/3QK3 w - - 0 1");
        assertEquals(100 - 950, see(board, "d1d5"));
    }

    @Test
    void aDefendedPieceIsStillWorthTakingWhenTheTradeIsGood() {
        // Rook takes a knight defended by a pawn: a rook for a knight and a pawn is roughly level,
        // and either way it is nothing like losing a rook outright.
        Board board = Board.fromFen("4k3/8/2p5/3n4/8/8/8/3RK3 w - - 0 1");
        int value = see(board, "d1d5");
        assertTrue(value > -300 && value <= 330,
                () -> "Rxd5 should read as a near-level trade, got " + value);
    }

    @Test
    void xRaysBehindTheCapturingPieceAreCounted() {
        // Two white rooks stacked on the d-file against one black rook on d8, defended by the king.
        // The rook behind only joins the exchange once the one in front has moved, which is the case
        // recomputing the attackers each round exists to handle.
        Board board = Board.fromFen("3rk3/8/8/8/8/8/3R4/3RK3 w - - 0 1");
        int value = see(board, "d2d8");
        assertTrue(value >= 0,
                () -> "The second rook makes the exchange sound; got " + value);
    }

    @Test
    void enPassantCapturesAPawn() {
        Board board = Board.fromFen("4k3/8/8/8/2p5/8/3P4/4K3 w - - 0 1");
        board.makeMove(Move.of(Squares.parse("d2"), Squares.parse("d4"), Move.DOUBLE_PUSH));
        // Black's cxd3 takes a pawn that nothing defends.
        assertEquals(100, see(board, "c4d3"));
    }

    @Test
    void quietMovesAreWorthNothing() {
        Board board = Board.startPosition();
        assertEquals(0, see(board, "e2e4"));
        assertEquals(0, see(board, "g1f3"));
    }

    @Test
    void promotionCountsThePieceItBecomes() {
        Board board = Board.fromFen("8/P7/8/8/8/8/8/K6k w - - 0 1");
        int value = see(board, "a7a8q");
        assertTrue(value > 700,
                () -> "Promoting to a queen with nothing to answer it should read high, got " + value);
    }

    private int see(Board board, String uci) {
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));
        for (int i = 0; i < count; i++) {
            if (Move.toUci(moves[i]).equals(uci)) {
                return exchange.evaluate(board, moves[i]);
            }
        }
        throw new AssertionError(uci + " is not legal in " + board.toFen());
    }
}
