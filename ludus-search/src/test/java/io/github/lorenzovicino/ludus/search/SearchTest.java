package io.github.lorenzovicino.ludus.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import io.github.lorenzovicino.ludus.eval.HandCraftedEvaluator;
import org.junit.jupiter.api.Test;

class SearchTest {

    private Search newSearch() {
        return new Search(new HandCraftedEvaluator());
    }

    @Test
    void findsMateInOne() {
        // Black king on g8 walled in by its own pawns; Ra8 covers the whole eighth rank.
        Board board = Board.fromFen("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1");
        SearchResult result = newSearch().search(board, SearchLimits.depth(3));

        assertEquals("a1a8", Move.toUci(result.bestMove()));
        assertTrue(Search.isMateScore(result.score()),
                () -> "Expected a mate score, got " + result.score());
        assertEquals(1, Search.mateInMoves(result.score()), "It is mate in one move");
    }

    @Test
    void prefersTheShorterMate() {
        // Searched four deep, the mate available right now must still be reported as mate in one.
        // Without the distance term in the mate score, every mate looks equally good and the engine
        // can circle a won position indefinitely.
        Board board = Board.fromFen("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1");
        SearchResult result = newSearch().search(board, SearchLimits.depth(4));
        assertEquals(1, Search.mateInMoves(result.score()));
    }

    @Test
    void stalemateScoresAsADraw() {
        // Black king h8, white queen f7, white king g6: every square is covered but h8 itself.
        Board board = Board.fromFen("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1");
        SearchResult result = newSearch().search(board, SearchLimits.depth(3));

        assertEquals(0, result.score(), "Stalemate is a draw, not a loss");
        assertEquals(Move.NONE, result.bestMove(), "There is no move to make");
    }

    @Test
    void takesFreeMaterial() {
        // The black queen on h5 sits on the rook's file with nothing in between, and nothing can
        // recapture.
        Board board = Board.fromFen("4k3/8/8/7q/8/8/8/K6R w - - 0 1");
        SearchResult result = newSearch().search(board, SearchLimits.depth(3));
        assertEquals("h1h5", Move.toUci(result.bestMove()));
    }

    @Test
    void leavesTheBoardExactlyAsItFoundIt() {
        // The search mutates the position as it works. If it ever failed to unwind completely, every
        // later move in the game would be computed from a corrupted board.
        Board board = Board.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        String before = board.stateSignature();

        newSearch().search(board, SearchLimits.depth(4));

        assertEquals(before, board.stateSignature());
    }

    @Test
    void respectsTheDepthLimit() {
        SearchResult result = newSearch().search(Board.startPosition(), SearchLimits.depth(3));
        assertEquals(3, result.depth());
        assertTrue(result.nodes() > 0);
    }

    @Test
    void alwaysReturnsALegalMove() {
        String[] positions = {
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
                "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
                "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
                "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
                "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
                "4k3/8/8/8/8/8/4P3/4K3 b - - 0 1",
        };
        for (String fen : positions) {
            Board board = Board.fromFen(fen);
            SearchResult result = newSearch().search(board, SearchLimits.depth(3));
            assertTrue(isLegal(board, result.bestMove()),
                    () -> "Search returned " + Move.toUci(result.bestMove())
                            + ", which is not legal in " + fen);
        }
    }

    @Test
    void reportsAPrincipalVariationOfLegalMoves() {
        Board board = Board.fromFen("r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10");
        Search search = newSearch();
        int[][] lastPv = new int[1][];
        search.setListener(info -> lastPv[0] = info.pv());

        search.search(board, SearchLimits.depth(4));

        assertTrue(lastPv[0] != null && lastPv[0].length > 0, "A completed iteration must report a PV");
        // Walking the variation proves it is a real line and not a stale buffer: each move has to be
        // legal in the position the previous one produced.
        for (int move : lastPv[0]) {
            assertTrue(isLegal(board, move),
                    () -> "PV move " + Move.toUci(move) + " is not legal at " + board.toFen());
            board.makeMove(move);
        }
    }

    @Test
    void honoursAMoveTimeBudget() {
        Board board = Board.fromFen("r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1");
        long start = System.nanoTime();
        SearchResult result = newSearch().search(board, SearchLimits.moveTime(300));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.hasMove(), "A timed search still has to produce a move");
        assertTrue(elapsedMillis < 3_000,
                () -> "A 300 ms budget overran badly: " + elapsedMillis + " ms");
    }

    private static boolean isLegal(Board board, int move) {
        if (move == Move.NONE) {
            return false;
        }
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));
        for (int i = 0; i < count; i++) {
            if (moves[i] == move) {
                return true;
            }
        }
        return false;
    }
}
