package io.github.lorenzovicino.ludus.uci;

import io.github.lorenzovicino.ludus.core.Attacks;
import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import io.github.lorenzovicino.ludus.core.Pieces;
import io.github.lorenzovicino.ludus.core.Squares;
import io.github.lorenzovicino.ludus.eval.Evaluator;
import io.github.lorenzovicino.ludus.eval.HandCraftedEvaluator;
import io.github.lorenzovicino.ludus.search.Search;
import io.github.lorenzovicino.ludus.search.SearchInfo;
import io.github.lorenzovicino.ludus.search.SearchLimits;
import io.github.lorenzovicino.ludus.search.SearchResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Speaks UCI, the protocol every chess GUI and match runner understands.
 *
 * <p>This class is the composition root: the one place that chooses which {@link Evaluator} the
 * search runs with. When the NNUE arrives it is selected here and nowhere else — see DESIGN.md §6.
 *
 * <p>Searches run on a single worker thread so {@code stop} can be honoured while one is in
 * progress. Any command that touches the board first waits for the running search to finish, since
 * the search mutates that board as it works.
 */
public final class UciEngine implements Runnable {

    private static final String NAME = "ludus 0.3.0";
    private static final String AUTHOR = "Lorenzo Vicino";
    private static final int DEFAULT_HASH_MB = 64;

    /** UCI's null move, the answer when a position has no legal move at all. */
    private static final String NO_MOVE = "0000";

    private final BufferedReader input;
    private final Consumer<String> output;
    private final Search search;
    private final ExecutorService worker;

    private Board board = Board.startPosition();
    private Future<?> running;
    private boolean quit;

    public UciEngine(BufferedReader input, Consumer<String> output) {
        this.input = input;
        this.output = output;
        this.search = new Search(new HandCraftedEvaluator());
        this.search.setListener(this::report);
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ludus-search");
            thread.setDaemon(true);
            return thread;
        });

        // Force the attack tables to build now. They come from a magic search on first use, and a
        // GUI should not be charged that cost on our clock during the first move of a game.
        Attacks.rook(Squares.A1, 0L);
    }

    @Override
    public void run() {
        try {
            String line;
            while (!quit && (line = input.readLine()) != null) {
                handle(line.trim());
            }
        } catch (IOException e) {
            // The host closed the pipe. Nothing left to talk to.
        } finally {
            search.requestStop();
            awaitSearch();
            worker.shutdownNow();
        }
    }

    void handle(String line) {
        if (line.isEmpty()) {
            return;
        }
        String[] tokens = line.split("\\s+");
        switch (tokens[0]) {
            case "uci" -> identify();
            // Answered straight away, even mid-search: the protocol uses this as a liveness probe
            // and a host is entitled to an immediate reply.
            case "isready" -> send("readyok");
            case "setoption" -> {
                awaitSearch();
                setOption(tokens);
            }
            case "ucinewgame" -> {
                awaitSearch();
                board = Board.startPosition();
                // The table and the history tables describe the game just finished. Carrying them
                // into an unrelated one is worse than starting empty.
                search.newGame();
            }
            case "position" -> {
                awaitSearch();
                setPosition(tokens);
            }
            case "go" -> go(tokens);
            case "stop" -> {
                search.requestStop();
                awaitSearch();
            }
            case "ponderhit" -> { /* Pondering is out of scope; see DESIGN.md §0. */ }
            case "quit" -> {
                search.requestStop();
                awaitSearch();
                quit = true;
            }
            // Not part of UCI. Useful enough by hand to be worth four lines.
            case "d", "board" -> send(board.toString());
            default -> { /* The protocol requires unknown commands to be ignored. */ }
        }
    }

    private void identify() {
        send("id name " + NAME);
        send("id author " + AUTHOR);
        send("option name Hash type spin default " + DEFAULT_HASH_MB + " min 1 max 1024");
        send("uciok");
    }

    /**
     * Handles {@code setoption name <id> value <x>}.
     *
     * <p>The option name can contain spaces, so everything between {@code name} and {@code value} is
     * the identifier. Unknown options are ignored rather than refused: a host is free to offer
     * options an engine has never heard of.
     */
    private void setOption(String[] tokens) {
        int nameStart = -1;
        int valueStart = -1;
        for (int i = 1; i < tokens.length; i++) {
            if (tokens[i].equals("name")) {
                nameStart = i + 1;
            } else if (tokens[i].equals("value")) {
                valueStart = i + 1;
                break;
            }
        }
        if (nameStart < 0 || valueStart < 0 || valueStart >= tokens.length) {
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(tokens, nameStart, valueStart - 1));
        if (!name.equalsIgnoreCase("Hash")) {
            return;
        }
        long megabytes = numberAfter(tokens, valueStart, DEFAULT_HASH_MB);
        int clamped = (int) Math.max(1, Math.min(megabytes, 1024));
        search.setHashSize(clamped);
        send("info string hash set to " + clamped + " MB");
    }

    private void setPosition(String[] tokens) {
        int i = 1;
        Board next;

        if (i < tokens.length && tokens[i].equals("startpos")) {
            next = Board.startPosition();
            i++;
        } else if (i < tokens.length && tokens[i].equals("fen")) {
            StringBuilder fen = new StringBuilder();
            for (i++; i < tokens.length && !tokens[i].equals("moves"); i++) {
                fen.append(tokens[i]).append(' ');
            }
            try {
                next = Board.fromFen(fen.toString().trim());
            } catch (RuntimeException e) {
                send("info string keeping previous position, bad FEN: " + e.getMessage());
                return;
            }
        } else {
            send("info string position needs startpos or fen");
            return;
        }

        if (i < tokens.length && tokens[i].equals("moves")) {
            for (i++; i < tokens.length; i++) {
                int move = findLegalMove(next, tokens[i]);
                if (move == Move.NONE) {
                    send("info string cannot play " + tokens[i] + ", replay stops here");
                    break;
                }
                next.makeMove(move);
            }
        }
        board = next;
    }

    /**
     * Resolves a UCI move string against the position's legal moves.
     *
     * <p>Matching a generated move rather than decoding the string is what makes this correct
     * without effort: the flags — capture, en passant, castling, which piece a promotion becomes —
     * come from the generator, which already knows. Inferring them from four characters and the
     * board is how engines end up mishandling en passant on move sixty.
     */
    private static int findLegalMove(Board board, String uci) {
        int[] moves = new int[MoveGenerator.MAX_MOVES];
        int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));
        for (int i = 0; i < count; i++) {
            if (Move.toUci(moves[i]).equals(uci)) {
                return moves[i];
            }
        }
        return Move.NONE;
    }

    private void go(String[] tokens) {
        awaitSearch();
        // Cleared here, on this thread, before the worker can start. Doing it inside the search
        // would let a `stop` that arrives in between go missing.
        search.clearStop();
        SearchLimits limits = parseLimits(tokens);
        running = worker.submit(() -> {
            try {
                SearchResult result = search.search(board, limits);
                send("bestmove " + (result.hasMove() ? Move.toUci(result.bestMove()) : NO_MOVE));
            } catch (RuntimeException e) {
                // A host left without a bestmove waits forever, so answer even when we failed.
                send("info string search failed: " + e);
                send("bestmove " + NO_MOVE);
            }
        });
    }

    private SearchLimits parseLimits(String[] tokens) {
        int depth = Search.MAX_DEPTH;
        long moveTime = -1;
        long whiteTime = -1;
        long blackTime = -1;
        long whiteIncrement = 0;
        long blackIncrement = 0;
        int movesToGo = 0;
        boolean infinite = false;

        for (int i = 1; i < tokens.length; i++) {
            switch (tokens[i]) {
                case "depth" -> depth = (int) numberAfter(tokens, ++i, depth);
                case "movetime" -> moveTime = numberAfter(tokens, ++i, -1);
                case "wtime" -> whiteTime = numberAfter(tokens, ++i, -1);
                case "btime" -> blackTime = numberAfter(tokens, ++i, -1);
                case "winc" -> whiteIncrement = numberAfter(tokens, ++i, 0);
                case "binc" -> blackIncrement = numberAfter(tokens, ++i, 0);
                case "movestogo" -> movesToGo = (int) numberAfter(tokens, ++i, 0);
                case "infinite" -> infinite = true;
                default -> { /* nodes, mate, searchmoves, ponder: not supported yet */ }
            }
        }

        if (infinite) {
            return SearchLimits.infinite();
        }
        if (moveTime > 0) {
            return SearchLimits.moveTime(moveTime);
        }
        boolean whiteToMove = board.sideToMove() == Pieces.WHITE;
        long remaining = whiteToMove ? whiteTime : blackTime;
        if (remaining >= 0) {
            return SearchLimits.clock(remaining, whiteToMove ? whiteIncrement : blackIncrement, movesToGo);
        }
        return SearchLimits.depth(depth);
    }

    private static long numberAfter(String[] tokens, int index, long fallback) {
        if (index >= tokens.length) {
            return fallback;
        }
        try {
            return Long.parseLong(tokens[index]);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void report(SearchInfo info) {
        StringBuilder line = new StringBuilder(112);
        line.append("info depth ").append(info.depth()).append(" score ");
        if (Search.isMateScore(info.score())) {
            line.append("mate ").append(Search.mateInMoves(info.score()));
        } else {
            line.append("cp ").append(info.score());
        }
        line.append(" nodes ").append(info.nodes())
                .append(" nps ").append(info.nodesPerSecond())
                .append(" time ").append(info.elapsedMillis());
        if (info.pv().length > 0) {
            line.append(" pv");
            for (int move : info.pv()) {
                line.append(' ').append(Move.toUci(move));
            }
        }
        send(line.toString());
    }

    private void awaitSearch() {
        Future<?> current = running;
        if (current == null) {
            return;
        }
        try {
            current.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            send("info string search thread failed: " + e.getCause());
        }
        running = null;
    }

    /** Serialised because the search thread reports while the reader thread may be answering. */
    private synchronized void send(String line) {
        output.accept(line);
    }
}
