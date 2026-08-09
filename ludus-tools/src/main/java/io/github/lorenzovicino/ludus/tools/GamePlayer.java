package io.github.lorenzovicino.ludus.tools;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import io.github.lorenzovicino.ludus.core.Pieces;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A pair of engines and the rules that decide when their game is over.
 *
 * <p>Owns two subprocesses and plays one opening twice with the colours swapped. Without the swap a
 * match measures the opening as much as the engines: a position that mildly favours White hands free
 * points to whoever drew White more often.
 *
 * <p>Adjudication uses this project's own board rather than either engine's opinion, so mate,
 * stalemate, the fifty-move rule and threefold repetition are all decided independently. An engine
 * that offers an illegal move loses that game and the fact is counted separately — quietly tolerating
 * one would hide the worst class of bug this harness can find.
 *
 * <p>Extracted from the in-process runner so the distributed worker plays by exactly the same rules.
 * Two copies of game adjudication would be two chances to disagree about who won.
 *
 * <p>Not thread-safe: engines are single-threaded processes, and one instance drives two of them.
 */
public final class GamePlayer implements AutoCloseable {

    /** The result of one opening played both ways, from engine A's point of view. */
    public record PairOutcome(int wins, int draws, int losses, int illegalByA, int illegalByB) {

        public static final PairOutcome NOTHING = new PairOutcome(0, 0, 0, 0, 0);

        public PairOutcome plus(PairOutcome other) {
            return new PairOutcome(
                    wins + other.wins,
                    draws + other.draws,
                    losses + other.losses,
                    illegalByA + other.illegalByA,
                    illegalByB + other.illegalByB);
        }

        public int games() {
            return wins + draws + losses;
        }
    }

    private final UciClient engineA;
    private final UciClient engineB;
    private final long moveTimeMillis;
    private final int maxPlies;
    private final Duration replyTimeout;

    public GamePlayer(List<String> commandA, List<String> commandB, long moveTimeMillis,
                      int maxPlies, Duration replyTimeout) throws IOException {
        this.moveTimeMillis = moveTimeMillis;
        this.maxPlies = maxPlies;
        this.replyTimeout = replyTimeout;

        UciClient a = null;
        try {
            a = start("A", commandA);
            this.engineA = a;
            this.engineB = start("B", commandB);
        } catch (IOException | RuntimeException e) {
            if (a != null) {
                a.close();
            }
            throw e;
        }
    }

    private UciClient start(String label, List<String> command) throws IOException {
        UciClient client = new UciClient(label, command);
        client.handshake(replyTimeout);
        client.awaitReady(replyTimeout);
        return client;
    }

    /** Plays {@code fen} twice, once with each engine as White. */
    public PairOutcome playPair(String fen) {
        PairOutcome first = tally(playGame(fen, engineA, engineB, true));
        PairOutcome second = tally(playGame(fen, engineB, engineA, false));
        return first.plus(second);
    }

    private Outcome playGame(String fen, UciClient white, UciClient black, boolean aIsWhite) {
        Board board = Board.fromFen(fen);
        List<String> played = new ArrayList<>();
        Map<Long, Integer> seen = new HashMap<>();
        seen.put(board.zobrist(), 1);

        int[] legal = new int[MoveGenerator.MAX_MOVES];
        white.newGame();
        black.newGame();

        while (true) {
            int legalCount =
                    MoveGenerator.filterLegal(board, legal, MoveGenerator.generate(board, legal));
            if (legalCount == 0) {
                if (!board.inCheck()) {
                    return Outcome.DRAW;
                }
                boolean whiteIsMated = board.sideToMove() == Pieces.WHITE;
                return whiteIsMated == aIsWhite ? Outcome.LOSS : Outcome.WIN;
            }
            if (board.isFiftyMoveDraw() || played.size() >= maxPlies) {
                return Outcome.DRAW;
            }

            boolean whiteToMove = board.sideToMove() == Pieces.WHITE;
            UciClient mover = whiteToMove ? white : black;
            boolean moverIsA = whiteToMove == aIsWhite;

            String uci;
            try {
                uci = mover.bestMove(fen, played, moveTimeMillis, replyTimeout);
            } catch (RuntimeException e) {
                System.err.printf("%s failed to move (%s); forfeiting the game%n",
                        moverIsA ? "A" : "B", e.getMessage());
                return moverIsA ? Outcome.LOSS : Outcome.WIN;
            }

            int move = match(legal, legalCount, uci);
            if (move == Move.NONE) {
                System.err.printf("ILLEGAL MOVE from %s: %s at %s%n",
                        moverIsA ? "A" : "B", uci, board.toFen());
                return moverIsA ? Outcome.ILLEGAL_BY_A : Outcome.ILLEGAL_BY_B;
            }

            board.makeMove(move);
            played.add(uci);

            if (seen.merge(board.zobrist(), 1, Integer::sum) >= 3) {
                return Outcome.DRAW;
            }
        }
    }

    private static PairOutcome tally(Outcome outcome) {
        return switch (outcome) {
            case WIN -> new PairOutcome(1, 0, 0, 0, 0);
            case DRAW -> new PairOutcome(0, 1, 0, 0, 0);
            case LOSS -> new PairOutcome(0, 0, 1, 0, 0);
            // An illegal move is a loss for whoever played it, and is also counted on its own so it
            // cannot hide inside the score.
            case ILLEGAL_BY_A -> new PairOutcome(0, 0, 1, 1, 0);
            case ILLEGAL_BY_B -> new PairOutcome(1, 0, 0, 0, 1);
        };
    }

    private static int match(int[] legal, int count, String uci) {
        for (int i = 0; i < count; i++) {
            if (Move.toUci(legal[i]).equals(uci)) {
                return legal[i];
            }
        }
        return Move.NONE;
    }

    @Override
    public void close() {
        try {
            engineA.close();
        } finally {
            engineB.close();
        }
    }

    private enum Outcome {
        WIN, DRAW, LOSS, ILLEGAL_BY_A, ILLEGAL_BY_B
    }
}
