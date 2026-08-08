package io.github.lorenzovicino.ludus.search;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import io.github.lorenzovicino.ludus.core.Pieces;
import io.github.lorenzovicino.ludus.core.Squares;
import io.github.lorenzovicino.ludus.core.StaticExchange;
import io.github.lorenzovicino.ludus.eval.Evaluator;
import java.util.Arrays;

/**
 * Negamax with alpha-beta, iterative deepening, a transposition table, and a quiescence search.
 *
 * <h2>What is here and why</h2>
 *
 * <p><strong>Quiescence search.</strong> The single largest gain of the milestone. Stopping the
 * search at a fixed depth means judging a position mid-exchange: the engine sees itself win a queen
 * and never sees the recapture one ply later. Quiescence keeps following captures until the position
 * is quiet, which removes the illusion. When the side to move is in check it searches every reply
 * instead, because a side in check has no option to stand still.
 *
 * <p><strong>Transposition table.</strong> The same position is reached by many move orders, and a
 * table turns the second visit into a lookup. It also supplies the previous iteration's best move,
 * which is the most valuable ordering hint available.
 *
 * <p><strong>Killers and history.</strong> Quiet moves have no material to sort them by. A move that
 * caused a cutoff at the same ply elsewhere in the tree usually does so again, and a move that has
 * caused cutoffs anywhere is a better guess than one that never has.
 *
 * <p><strong>Static exchange evaluation.</strong> Splits captures into those worth searching and
 * those that lose material. Losing captures sort behind quiet moves, and quiescence declines them
 * outright.
 *
 * <h2>What is still absent</h2>
 *
 * <p>No null-move pruning, no late move reductions, no futility pruning. Those are M3, one patch at
 * a time with its own measurement, because each of them can hide a bug that only loses games in rare
 * positions.
 *
 * <h2>Threading</h2>
 *
 * <p>Single-threaded; one instance runs one search at a time. {@link #requestStop()} is the only
 * method safe to call from elsewhere.
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

    private static final int TT_MOVE_SCORE = 3_000_000;
    private static final int GOOD_CAPTURE_BASE = 2_000_000;
    private static final int KILLER_PRIMARY = 1_000_000;
    private static final int KILLER_SECONDARY = 900_000;
    private static final int BAD_CAPTURE_BASE = -2_000_000;
    /** Kept below {@link #KILLER_SECONDARY} so a well-tried quiet move never outranks a killer. */
    private static final int HISTORY_MAX = 500_000;

    private final Evaluator evaluator;
    private final StaticExchange exchange = new StaticExchange();
    private TranspositionTable table = new TranspositionTable();

    // Preallocated, one per ply. The search must not allocate — see DESIGN.md §3.3.
    private final int[][] moveLists = new int[MAX_DEPTH + 2][MoveGenerator.MAX_MOVES];
    private final int[][] orderingScores = new int[MAX_DEPTH + 2][MoveGenerator.MAX_MOVES];
    private final int[][] principalVariation = new int[MAX_DEPTH + 2][MAX_DEPTH + 2];
    private final int[] pvLength = new int[MAX_DEPTH + 2];
    private final int[][] killers = new int[MAX_DEPTH + 2][2];
    private final int[][][] history =
            new int[Pieces.COLOR_COUNT][Squares.COUNT][Squares.COUNT];

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

    /** Resizes the transposition table, discarding its contents. */
    public void setHashSize(int megabytes) {
        table.resize(megabytes);
    }

    /** Forgets everything learned from the previous game. */
    public void newGame() {
        table.clear();
        for (int[][] byFrom : history) {
            for (int[] row : byFrom) {
                Arrays.fill(row, 0);
            }
        }
        for (int[] pair : killers) {
            Arrays.fill(pair, Move.NONE);
        }
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
        table.newSearch();

        // Killers are per-ply guesses about this tree, so they start empty. History is a longer-run
        // statistic and is halved instead: what worked last move usually still helps, but it should
        // not outweigh what this search discovers.
        for (int[] pair : killers) {
            Arrays.fill(pair, Move.NONE);
        }
        decayHistory();

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

        // Checked before the table, deliberately. A draw by repetition depends on the path taken,
        // and the table stores positions without their history, so asking it first could report a
        // winning score for a position that is actually a draw right now.
        if (ply > 0 && (board.isRepetition() || board.isFiftyMoveDraw())) {
            return DRAW;
        }

        nodes++;
        if ((nodes & (TIME_CHECK_INTERVAL - 1)) == 0 && isOutOfTime()) {
            aborted = true;
            return DRAW;
        }

        if (depth <= 0) {
            return quiescence(board, ply, alpha, beta);
        }

        long key = board.zobrist();
        long entry = table.probe(key);
        int tableMove = Move.NONE;
        if (entry != 0) {
            tableMove = TranspositionTable.moveOf(entry);
            // No cutoff at the root: the host asked for a move, and a cutoff returns a score
            // without setting one.
            if (ply > 0 && TranspositionTable.depthOf(entry) >= depth) {
                int tableScore = TranspositionTable.scoreOf(entry, ply);
                int bound = TranspositionTable.boundOf(entry);
                if (bound == TranspositionTable.BOUND_EXACT
                        || (bound == TranspositionTable.BOUND_LOWER && tableScore >= beta)
                        || (bound == TranspositionTable.BOUND_UPPER && tableScore <= alpha)) {
                    return tableScore;
                }
            }
        }

        int originalAlpha = alpha;
        int[] moves = moveLists[ply];
        int count = MoveGenerator.generate(board, moves);
        order(board, moves, orderingScores[ply], count, ply, tableMove);

        int us = board.sideToMove();
        int legalMoves = 0;
        int best = -INFINITY;
        int bestHere = Move.NONE;

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
                bestHere = move;
                if (score > alpha) {
                    alpha = score;
                    recordPv(ply, move);
                    if (alpha >= beta) {
                        rememberCutoff(us, move, ply, depth);
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

        int bound = best <= originalAlpha ? TranspositionTable.BOUND_UPPER
                : best >= beta ? TranspositionTable.BOUND_LOWER
                : TranspositionTable.BOUND_EXACT;
        table.store(key, bestHere, best, depth, bound, ply);
        return best;
    }

    /**
     * Searches on past the nominal depth until nothing is left to capture.
     *
     * <p>Without this the engine evaluates positions in the middle of an exchange and believes
     * whatever the half-finished trade suggests. It is the difference between an engine that can be
     * beaten by a two-move combination and one that cannot.
     */
    private int quiescence(Board board, int ply, int alpha, int beta) {
        if (aborted) {
            return DRAW;
        }
        nodes++;
        if ((nodes & (TIME_CHECK_INTERVAL - 1)) == 0 && isOutOfTime()) {
            aborted = true;
            return DRAW;
        }
        if (ply >= MAX_DEPTH) {
            return evaluator.evaluate(board);
        }

        boolean inCheck = board.inCheck();
        int best;
        if (inCheck) {
            // Standing pat is not available when in check: doing nothing is not a legal option, so
            // the static score would describe a position that cannot occur.
            best = -INFINITY;
        } else {
            best = evaluator.evaluate(board);
            if (best >= beta) {
                return best;
            }
            if (best > alpha) {
                alpha = best;
            }
        }

        int[] moves = moveLists[ply];
        int count = MoveGenerator.generate(board, moves);
        if (!inCheck) {
            count = keepTactical(moves, count);
        }
        order(board, moves, orderingScores[ply], count, ply, Move.NONE);

        int us = board.sideToMove();
        int legalMoves = 0;

        for (int i = 0; i < count; i++) {
            int move = moves[i];

            // A capture that loses material cannot improve the score of a quiet position, and
            // searching it invites an endless chain of bad trades. Evasions are exempt: when in
            // check even a losing capture may be the only legal move.
            if (!inCheck && exchange.evaluate(board, move) < 0) {
                continue;
            }

            evaluator.beforeMakeMove(board, move);
            board.makeMove(move);
            if (board.isKingAttacked(us)) {
                board.unmakeMove(move);
                evaluator.afterUnmakeMove(board, move);
                continue;
            }
            legalMoves++;

            int score = -quiescence(board, ply + 1, -beta, -alpha);

            board.unmakeMove(move);
            evaluator.afterUnmakeMove(board, move);

            if (aborted) {
                return DRAW;
            }

            if (score > best) {
                best = score;
                if (score > alpha) {
                    alpha = score;
                    if (alpha >= beta) {
                        break;
                    }
                }
            }
        }

        // Mate found while chasing captures. Only reachable in the in-check branch, since otherwise
        // quiet moves were filtered out and an empty list means nothing to capture, not no moves.
        if (inCheck && legalMoves == 0) {
            return -MATE + ply;
        }
        return best;
    }

    private static int keepTactical(int[] moves, int count) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (Move.isCapture(moves[i]) || Move.isPromotion(moves[i])) {
                moves[kept++] = moves[i];
            }
        }
        return kept;
    }

    /**
     * Sorts the move list best-guess first.
     *
     * <p>Ordering earns more than almost any other single change: alpha-beta visits roughly the
     * square root of the tree when the best move comes first, and close to all of it when the best
     * move comes last.
     */
    private void order(Board board, int[] moves, int[] scores, int count, int ply, int tableMove) {
        for (int i = 0; i < count; i++) {
            scores[i] = orderingScore(board, moves[i], ply, tableMove);
        }
        // Insertion sort: the lists are short, and this allocates nothing and has the smallest
        // constant of anything that would work here.
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

    private int orderingScore(Board board, int move, int ply, int tableMove) {
        if (move != Move.NONE && move == tableMove) {
            return TT_MOVE_SCORE;
        }
        if (Move.isCapture(move) || Move.isPromotion(move)) {
            int value = exchange.evaluate(board, move);
            return (value >= 0 ? GOOD_CAPTURE_BASE : BAD_CAPTURE_BASE) + value;
        }
        if (move == killers[ply][0]) {
            return KILLER_PRIMARY;
        }
        if (move == killers[ply][1]) {
            return KILLER_SECONDARY;
        }
        return history[board.sideToMove()][Move.from(move)][Move.to(move)];
    }

    /**
     * Records that a quiet move produced a cutoff.
     *
     * <p>Captures are excluded because material already sorts them; spending the tables on them
     * would drown out the only signal quiet moves have. The history bonus grows with the square of
     * the depth, so a cutoff near the root — where it saved far more work — counts for more.
     */
    private void rememberCutoff(int color, int move, int ply, int depth) {
        if (Move.isCapture(move) || Move.isPromotion(move)) {
            return;
        }
        if (killers[ply][0] != move) {
            killers[ply][1] = killers[ply][0];
            killers[ply][0] = move;
        }
        int[] row = history[color][Move.from(move)];
        int to = Move.to(move);
        row[to] = Math.min(HISTORY_MAX, row[to] + depth * depth);
    }

    private void decayHistory() {
        for (int[][] byFrom : history) {
            for (int[] row : byFrom) {
                for (int i = 0; i < row.length; i++) {
                    row[i] >>= 1;
                }
            }
        }
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
