package io.github.lorenzovicino.ludus.tools;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays two engines against each other and reports what the result implies.
 *
 * <p>Every opening is played twice with the colours swapped. Without that, a match measures the
 * opening book as much as the engines: a position that mildly favours white hands free points to
 * whoever drew white more often.
 *
 * <p>Adjudication uses this project's own board rather than trusting either engine's opinion, so a
 * mate, a stalemate, the fifty-move rule and a threefold repetition are all decided independently.
 * An engine that offers an illegal move loses the game on the spot and the fact is reported: quietly
 * tolerating one would hide the worst class of bug this harness can find.
 */
public final class MatchRunner {

    /**
     * @param moveTimeMillis a fixed allowance per move. Fixed time rather than a running clock,
     *                       deliberately: it isolates what the search does with the time it gets from
     *                       how well each version divides a clock, which are separate questions and
     *                       would otherwise be measured together.
     * @param maxPlies       cap for a game neither side can finish
     * @param stopOnVerdict  end the match as soon as a bound is crossed. True is what you want when
     *                       gating a patch: the test has answered and further games cost time for
     *                       nothing. False plays every opening, which is what you want when the
     *                       headline number matters — a test that stops at eleven games has decided
     *                       the question but pinned the Elo only to within a few hundred points
     */
    public record Config(int openingPairs, long moveTimeMillis, int maxPlies, int concurrency,
                         boolean stopOnVerdict, Duration replyTimeout) {
    }

    /**
     * @param verdict      the verdict as it stood when the test decided, or at the end if it never did
     * @param stoppedEarly whether a bound was crossed rather than the book running out
     */
    public record Result(int wins, int draws, int losses, int illegalByA, int illegalByB,
                         Sprt.Verdict verdict, boolean stoppedEarly) {
    }

    private final List<String> commandA;
    private final List<String> commandB;
    private final List<String> openings;
    private final Config config;
    private final Sprt sprt;

    private final Object lock = new Object();
    private int wins;
    private int draws;
    private int losses;
    private int illegalByA;
    private int illegalByB;
    private int finished;
    private final AtomicBoolean stop = new AtomicBoolean();

    // The verdict is latched the instant a bound is crossed, together with the tally that crossed it.
    //
    // Recomputing it after every worker has drained would be wrong, and wrong in a way that reads as
    // correct. Games already in flight keep finishing after the stop is signalled, and a single late
    // loss can pull the ratio back inside the bounds — turning a test that had decided into one that
    // reports "inconclusive". The evidence that crossed the bound does not stop being evidence.
    private Sprt.Verdict decidedVerdict;
    private int decidedWins;
    private int decidedDraws;
    private int decidedLosses;

    public MatchRunner(List<String> commandA, List<String> commandB, List<String> openings,
                       Config config, Sprt sprt) {
        this.commandA = commandA;
        this.commandB = commandB;
        this.openings = openings;
        this.config = config;
        this.sprt = sprt;
    }

    /** @return the result from A's point of view */
    public Result run() throws InterruptedException {
        Queue<String> pending = new ConcurrentLinkedQueue<>(
                openings.subList(0, Math.min(config.openingPairs(), openings.size())));

        int workers = Math.max(1, config.concurrency());
        CountDownLatch done = new CountDownLatch(workers);

        for (int i = 0; i < workers; i++) {
            Thread worker = new Thread(() -> {
                try {
                    workLoop(pending);
                } catch (RuntimeException e) {
                    System.err.println("worker stopped: " + e.getMessage());
                } finally {
                    done.countDown();
                }
            }, "match-worker-" + i);
            worker.setDaemon(true);
            worker.start();
        }
        done.await();

        synchronized (lock) {
            if (decidedVerdict == null) {
                return new Result(wins, draws, losses, illegalByA, illegalByB,
                        sprt.verdict(wins, draws, losses), false);
            }
            if (config.stopOnVerdict()) {
                // The tally that crossed the bound is the evidence the verdict rests on.
                return new Result(decidedWins, decidedDraws, decidedLosses,
                        illegalByA, illegalByB, decidedVerdict, true);
            }
            // Fixed length: keep the verdict that was reached, but report the whole match, because
            // the reason to play every opening was to pin the Elo down.
            return new Result(wins, draws, losses, illegalByA, illegalByB, decidedVerdict, false);
        }
    }

    private void workLoop(Queue<String> pending) {
        // Each worker owns its own pair of processes: engines are stateful and single-threaded, and
        // sharing one across concurrent games would interleave two searches in one engine.
        try (UciClient engineA = start("A", commandA);
             UciClient engineB = start("B", commandB)) {

            String opening;
            while (!stop.get() && (opening = pending.poll()) != null) {
                // Colours swapped between the two games so the opening favours neither engine.
                record(playGame(opening, engineA, engineB, true));
                if (stop.get()) {
                    break;
                }
                record(playGame(opening, engineB, engineA, false));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not start an engine: " + e.getMessage(), e);
        }
    }

    private UciClient start(String label, List<String> command) throws IOException {
        UciClient client = new UciClient(label, command);
        client.handshake(config.replyTimeout());
        client.awaitReady(config.replyTimeout());
        return client;
    }

    /** @return the outcome from A's point of view */
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
                boolean whiteIsMated = board.sideToMove() == io.github.lorenzovicino.ludus.core.Pieces.WHITE;
                boolean aLost = whiteIsMated == aIsWhite;
                return aLost ? Outcome.LOSS : Outcome.WIN;
            }
            if (board.isFiftyMoveDraw() || played.size() >= config.maxPlies()) {
                return Outcome.DRAW;
            }

            boolean whiteToMove =
                    board.sideToMove() == io.github.lorenzovicino.ludus.core.Pieces.WHITE;
            UciClient mover = whiteToMove ? white : black;
            boolean moverIsA = whiteToMove == aIsWhite;

            String uci;
            try {
                uci = mover.bestMove(fen, played, config.moveTimeMillis(), config.replyTimeout());
            } catch (RuntimeException e) {
                System.err.printf("%s failed to move (%s); forfeiting the game%n",
                        moverIsA ? "A" : "B", e.getMessage());
                return moverIsA ? Outcome.LOSS : Outcome.WIN;
            }

            int move = match(legal, legalCount, uci);
            if (move == Move.NONE) {
                System.err.printf("ILLEGAL MOVE from %s: %s at %s%n", moverIsA ? "A" : "B", uci,
                        board.toFen());
                return moverIsA ? Outcome.ILLEGAL_BY_A : Outcome.ILLEGAL_BY_B;
            }

            board.makeMove(move);
            played.add(uci);

            int count = seen.merge(board.zobrist(), 1, Integer::sum);
            if (count >= 3) {
                return Outcome.DRAW;
            }
        }
    }

    private static int match(int[] legal, int count, String uci) {
        for (int i = 0; i < count; i++) {
            if (Move.toUci(legal[i]).equals(uci)) {
                return legal[i];
            }
        }
        return Move.NONE;
    }

    private void record(Outcome outcome) {
        synchronized (lock) {
            switch (outcome) {
                case WIN -> wins++;
                case DRAW -> draws++;
                case LOSS -> losses++;
                // An illegal move is a loss for whoever played it, and is also counted separately so
                // it cannot hide inside the score.
                case ILLEGAL_BY_A -> {
                    losses++;
                    illegalByA++;
                }
                case ILLEGAL_BY_B -> {
                    wins++;
                    illegalByB++;
                }
            }
            finished++;

            double llr = sprt.logLikelihoodRatio(wins, draws, losses);
            System.out.printf("game %3d  W-D-L %3d-%3d-%3d  LLR %+.2f  [%.2f, %.2f]  %s%n",
                    finished, wins, draws, losses, llr, sprt.lowerBound(), sprt.upperBound(),
                    EloEstimate.of(wins, draws, losses));

            if (decidedVerdict == null) {
                Sprt.Verdict verdict = sprt.verdict(wins, draws, losses);
                if (verdict != Sprt.Verdict.INCONCLUSIVE) {
                    decidedVerdict = verdict;
                    decidedWins = wins;
                    decidedDraws = draws;
                    decidedLosses = losses;
                    if (config.stopOnVerdict()) {
                        stop.set(true);
                    }
                    System.out.printf("--- bound crossed at game %d: %s ---%n", finished, verdict);
                }
            }
        }
    }

    private enum Outcome {
        WIN, DRAW, LOSS, ILLEGAL_BY_A, ILLEGAL_BY_B
    }
}
