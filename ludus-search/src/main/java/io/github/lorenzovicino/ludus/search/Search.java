package io.github.lorenzovicino.ludus.search;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import io.github.lorenzovicino.ludus.core.Pieces;
import io.github.lorenzovicino.ludus.eval.Evaluator;
import java.util.Arrays;

/**
 * Negamax with alpha-beta, driven by iterative deepening.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>No quiescence search, no transposition table, no killers or history, no pruning or reductions.
 * All of that is M2, and holding it back is the point rather than an oversight: M2's exit criterion
 * is beating this version by SPRT, which is only a real measurement if this version exists first.
 * The engine is consequently tactically blind at the horizon — it will happily walk into a
 * recapture one ply beyond where it stopped looking. Known, deliberate, and the first thing M2
 * fixes.
 *
 * <p>Capture ordering is here, though, because alpha-beta without it barely prunes: the same
 * position that reaches depth 6 with good ordering reaches depth 4 without, and comparing an
 * unordered search against an ordered one would measure the ordering rather than everything else.
 *
 * <h2>Threading</h2>
 *
 * <p>Single-threaded, and one instance runs one search at a time — it owns preallocated buffers and
 * mutates the board it is given. {@link #requestStop()} is the exception, and is safe to call from
 * another thread.
 */
public final class Search {

    public static final int MAX_DEPTH = 64;

    /** Score of a mate delivered immediately. Deeper mates score lower by their distance. */
    public static final int MATE = 30_000;

    /** Scores at least this large are forced mates, and their negatives are forced losses. */
    public static final int MATE_THRESHOLD = MATE - 2 * MAX_DEPTH;

    private static final int INFINITY = 32_000;
    private static final int DRAW = 0;

    /** Nodes between clock reads. A power of two so the test is a mask, not a division. */
    private static final int TIME_CHECK_INTERVAL = 4096;

    /** Ordering values only. The king's entry exists so it never sorts as a cheap victim. */
    private static final int[] EXCHANGE_VALUE = {100, 320, 330, 500, 950, 20_000};

    private static final int CAPTURE_BONUS = 100_000;
    private static final int PROMOTION_BONUS = 10_000;

    private final Evaluator evaluator;

    // One buffer per ply, allocated once. The search must not allocate — see DESIGN.md §3.3.
    private final int[][] moveLists = new int[MAX_DEPTH + 2][MoveGenerator.MAX_MOVES];
    private final int[][] orderingScores = new int[MAX_DEPTH + 2][MoveGenerator.MAX_MOVES];
    private final int[][] principalVariation = new int[MAX_DEPTH + 2][MAX_DEPTH + 2];
    private final int[] pvLength = new int[MAX_DEPTH + 2];

    private SearchListener listener = SearchListener.NONE;
    private long nodes;
    private long hardDeadline;
    private volatile boolean stopRequested;
    private boolean aborted;

    public Search(Evaluator evaluator) {
        this.evaluator = evaluator;
    }

    public void setListener(SearchListener listener) {
        this.listener = listener == null ? SearchListener.NONE : listener;
    }

    /** Asks the running search to stop as soon as it notices. Safe from any thread. */
    public void requestStop() {
        stopRequested = true;
    }

    /**
     * Clears a previous stop request. Call it before handing a search to a worker thread — never
     * from inside {@link #search}.
     *
     * <p>The distinction is not cosmetic. If the search cleared the flag itself, a stop arriving
     * between the moment the search was submitted and its first instruction would be erased, and an
     * {@code go infinite} would then run until the process died with the host still waiting for a
     * {@code bestmove}. Clearing it on the thread that submits the search removes the window.
     */
    public void clearStop() {
        stopRequested = false;
    }

    /**
     * Searches {@code board} within {@code limits} and returns the move to play.
     *
     * <p>The board is left exactly as it was found: every move made is unmade.
     */
    public SearchResult search(Board board, SearchLimits limits) {
        nodes = 0;
        aborted = false;
        evaluator.reset(board);

        long start = System.nanoTime();
        hardDeadline = limits.hardNanos() == SearchLimits.UNLIMITED
                ? Long.MAX_VALUE
                : start + limits.hardNanos();
        long softDeadline = limits.softNanos() == SearchLimits.UNLIMITED
                ? Long.MAX_VALUE
                : start + limits.softNanos();

        int bestMove = Move.NONE;
        int bestScore = DRAW;
        int completedDepth = 0;
        int deepest = Math.min(limits.depth(), MAX_DEPTH);

        for (int depth = 1; depth <= deepest; depth++) {
            int score = negamax(board, depth, 0, -INFINITY, INFINITY);

            // An abandoned iteration is discarded whole. It searched the moves it happened to try
            // first and nothing else, so the move on top of it may be worse than what the last
            // complete iteration already established.
            if (aborted) {
                break;
            }

            bestScore = score;
            bestMove = principalVariation[0][0];
            completedDepth = depth;
            listener.onIterationComplete(
                    new SearchInfo(depth, score, nodes, millisSince(start), currentPv()));

            if (System.nanoTime() >= softDeadline) {
                break;
            }
            // Iterative deepening finds the shortest mate first — a mate in three would have
            // surfaced at depth three — so once one appears there is nothing better to find.
            if (isMateScore(score)) {
                break;
            }
        }

        // A UCI host must always be answered with a move. If the very first iteration was cut off
        // before it finished anything, fall back to any legal move rather than reporting none.
        if (bestMove == Move.NONE) {
            bestMove = firstLegalMove(board);
        }
        return new SearchResult(bestMove, bestScore, nodes, completedDepth);
    }

    private int negamax(Board board, int depth, int ply, int alpha, int beta) {
        pvLength[ply] = 0;

        if (aborted) {
            return DRAW;
        }

        // A repeated position or an exhausted fifty-move counter is a draw whatever the pieces are
        // worth. Not tested at the root, where the host asked for a move rather than a verdict.
        if (ply > 0 && (board.isRepetition() || board.isFiftyMoveDraw())) {
            return DRAW;
        }

        nodes++;
        if ((nodes & (TIME_CHECK_INTERVAL - 1)) == 0 && isOutOfTime()) {
            aborted = true;
            return DRAW;
        }

        if (depth <= 0) {
            return evaluator.evaluate(board);
        }

        int[] moves = moveLists[ply];
        int count = MoveGenerator.generate(board, moves);
        order(board, moves, orderingScores[ply], count);

        int us = board.sideToMove();
        int legalMoves = 0;
        int best = -INFINITY;

        for (int i = 0; i < count; i++) {
            int move = moves[i];

            evaluator.beforeMakeMove(board, move);
            board.makeMove(move);
            if (board.isKingAttacked(us)) {
                board.unmakeMove(move);
                evaluator.afterUnmakeMove(board, move);
                continue;
            }
            legalMoves++;

            int score = -negamax(board, depth - 1, ply + 1, -beta, -alpha);

            board.unmakeMove(move);
            evaluator.afterUnmakeMove(board, move);

            if (aborted) {
                return DRAW;
            }

            if (score > best) {
                best = score;
                if (score > alpha) {
                    alpha = score;
                    recordPv(ply, move);
                    if (alpha >= beta) {
                        break;
                    }
                }
            }
        }

        if (legalMoves == 0) {
            // The mate score carries its distance from the root, so a mate in three outranks a mate
            // in five. Without the ply term every mate looks equally good and the engine can shuffle
            // indefinitely in a won position.
            return board.inCheck() ? -MATE + ply : DRAW;
        }
        return best;
    }

    /**
     * Sorts captures and promotions ahead of quiet moves — most valuable victim first, cheapest
     * attacker among equal victims.
     *
     * <p>Ordering earns more than almost any other single change: alpha-beta visits roughly the
     * square root of the tree when the best move comes first, and close to all of it when the best
     * move comes last.
     */
    private static void order(Board board, int[] moves, int[] scores, int count) {
        for (int i = 0; i < count; i++) {
            scores[i] = orderingScore(board, moves[i]);
        }
        // Insertion sort: the lists are short, mostly quiet moves scoring zero, and this allocates
        // nothing and has the smallest constant of anything that would work here.
        for (int i = 1; i < count; i++) {
            int move = moves[i];
            int score = scores[i];
            int j = i - 1;
            while (j >= 0 && scores[j] < score) {
                moves[j + 1] = moves[j];
                scores[j + 1] = scores[j];
                j--;
            }
            moves[j + 1] = move;
            scores[j + 1] = score;
        }
    }

    private static int orderingScore(Board board, int move) {
        int score = 0;
        if (Move.isPromotion(move)) {
            score += PROMOTION_BONUS + EXCHANGE_VALUE[Move.promotionType(move)];
        }
        if (Move.isCapture(move)) {
            // En passant takes a pawn that is not on the destination square, so the victim cannot be
            // read off the board there.
            int victim = Move.isEnPassant(move)
                    ? Pieces.PAWN
                    : Pieces.typeOf(board.pieceAt(Move.to(move)));
            int attacker = Pieces.typeOf(board.pieceAt(Move.from(move)));
            score += CAPTURE_BONUS + EXCHANGE_VALUE[victim] * 8 - EXCHANGE_VALUE[attacker];
        }
        return score;
    }

    private void recordPv(int ply, int move) {
        principalVariation[ply][0] = move;
        int childLength = pvLength[ply + 1];
        System.arraycopy(principalVariation[ply + 1], 0, principalVariation[ply], 1, childLength);
        pvLength[ply] = childLength + 1;
    }

    private int[] currentPv() {
        return Arrays.copyOf(principalVariation[0], pvLength[0]);
    }

    private int firstLegalMove(Board board) {
        int[] moves = moveLists[0];
        int count = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));
        return count == 0 ? Move.NONE : moves[0];
    }

    private boolean isOutOfTime() {
        return stopRequested || System.nanoTime() >= hardDeadline;
    }

    private static long millisSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public static boolean isMateScore(int score) {
        return Math.abs(score) >= MATE_THRESHOLD;
    }

    /**
     * Moves — not plies — to the mate a score describes. Positive when the side to move delivers it,
     * negative when it receives it.
     */
    public static int mateInMoves(int score) {
        return score > 0 ? (MATE - score + 1) / 2 : -((MATE + score + 1) / 2);
    }
}
