package io.github.lorenzovicino.ludus.uci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden input and output over the protocol.
 *
 * <p>A GUI is unforgiving in a specific way: it will wait forever rather than complain. An engine
 * that fails to answer {@code readyok}, or that finishes a search without emitting a
 * {@code bestmove}, simply hangs the game with no error anywhere. These tests are the cheapest way
 * to be sure that never happens.
 */
class UciProtocolTest {

    private static final String MATE_IN_ONE = "6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1";
    private static final String STALEMATE = "7k/5Q2/6K1/8/8/8/8/8 b - - 0 1";

    @Test
    void identifiesItself() {
        List<String> replies = converse("uci", "quit");

        assertTrue(replies.stream().anyMatch(line -> line.startsWith("id name ")), "Missing id name");
        assertTrue(replies.stream().anyMatch(line -> line.startsWith("id author ")), "Missing id author");
        assertTrue(replies.contains("uciok"), "Missing uciok");
    }

    @Test
    void answersTheLivenessProbe() {
        assertTrue(converse("isready", "quit").contains("readyok"));
    }

    @Test
    void returnsALegalMoveFromTheStartPosition() {
        List<String> replies = converse("position startpos", "go depth 3", "quit");
        assertMoveIsLegal(Board.startPosition(), bestMove(replies));
    }

    @Test
    void followsAMoveListFromTheStartPosition() {
        List<String> replies =
                converse("position startpos moves e2e4 e7e5 g1f3", "go depth 3", "quit");

        Board expected = Board.startPosition();
        for (String uci : new String[] {"e2e4", "e7e5", "g1f3"}) {
            expected.makeMove(findMove(expected, uci));
        }
        assertMoveIsLegal(expected, bestMove(replies));
    }

    @Test
    void acceptsAPositionGivenAsFen() {
        List<String> replies = converse("position fen " + MATE_IN_ONE, "go depth 3", "quit");
        assertEquals("a1a8", bestMove(replies));
    }

    @Test
    void reportsAMateScoreInItsInfoLines() {
        List<String> replies = converse("position fen " + MATE_IN_ONE, "go depth 3", "quit");
        assertTrue(replies.stream().anyMatch(line -> line.contains("score mate 1")),
                () -> "No mate score reported. Output was:\n" + String.join("\n", replies));
    }

    @Test
    void infoLinesCarryDepthNodesAndAPrincipalVariation() {
        List<String> replies = converse("position startpos", "go depth 4", "quit");
        String info = replies.stream()
                .filter(line -> line.startsWith("info depth "))
                .reduce((first, second) -> second)
                .orElse(null);

        assertNotNull(info, "No info line was emitted");
        assertTrue(info.contains(" nodes "), () -> "Missing nodes: " + info);
        assertTrue(info.contains(" nps "), () -> "Missing nps: " + info);
        assertTrue(info.contains(" pv "), () -> "Missing pv: " + info);
    }

    @Test
    void answersTheNullMoveWhenThereIsNothingToPlay() {
        // Stalemate: the host still needs an answer, and 0000 is what the protocol reserves for it.
        List<String> replies = converse("position fen " + STALEMATE, "go depth 3", "quit");
        assertEquals("0000", bestMove(replies));
    }

    @Test
    void stopEndsAnInfiniteSearch() {
        // Without a working stop this test would never return, which is exactly the failure a GUI
        // would experience.
        List<String> replies = converse("position startpos", "go infinite", "stop", "quit");
        assertMoveIsLegal(Board.startPosition(), bestMove(replies));
    }

    @Test
    void keepsGoingAfterAnUnparseableFen() {
        List<String> replies = converse(
                "position fen totally not a fen",
                "position startpos",
                "go depth 2",
                "quit");
        assertMoveIsLegal(Board.startPosition(), bestMove(replies));
    }

    @Test
    void ignoresCommandsItDoesNotKnow() {
        List<String> replies = converse("frobnicate", "", "   ", "isready", "quit");
        assertTrue(replies.contains("readyok"), "Unknown commands must not derail the loop");
    }

    @Test
    void ucinewgameResetsToTheStartPosition() {
        List<String> replies = converse(
                "position fen " + MATE_IN_ONE,
                "ucinewgame",
                "go depth 2",
                "quit");
        assertMoveIsLegal(Board.startPosition(), bestMove(replies));
    }

    @Test
    void survivesEndOfInputWithoutQuit() {
        // A host that dies mid-game closes the pipe rather than saying goodbye.
        List<String> replies = converse("position startpos", "go depth 2");
        assertMoveIsLegal(Board.startPosition(), bestMove(replies));
    }

    private static List<String> converse(String... commands) {
        String script = String.join("\n", commands) + "\n";
        BufferedReader input = new BufferedReader(new StringReader(script));
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        new UciEngine(input, output::add).run();
        return List.copyOf(output);
    }

    private static String bestMove(List<String> replies) {
        String line = replies.stream()
                .filter(reply -> reply.startsWith("bestmove "))
                .findFirst()
                .orElse(null);
        assertNotNull(line, () -> "No bestmove was emitted. Output was:\n" + String.join("\n", replies));
        return line.split("\\s+")[1];
    }

    private static void assertMoveIsLegal(Board board, String uci) {
        assertTrue(findMove(board, uci) != Move.NONE,
                () -> uci + " is not legal in " + board.toFen());
    }

    private static int findMove(Board board, String uci) {
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));
        for (int i = 0; i < count; i++) {
            if (Move.toUci(moves[i]).equals(uci)) {
                return moves[i];
            }
        }
        return Move.NONE;
    }
}
