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

    /** Below this depth a null move saves less than the search it costs. */
    private static final int NULL_MOVE_MIN_DEPTH = 3;
    private static final int NULL_MOVE_BASE_REDUCTION = 2;

    /**
     * How shallow a node has to be before its static score is trusted to stand in for a search, and how
     * much slack that trust is given per remaining ply.
     *
     * <p>Both numbers were guesses, and they were written down as guesses because null move pruning was
     * rejected here at −16 Elo after looking every bit as obviously correct. **Measured: +60.7 ± 27.6
     * Elo** over 486 games, 231-108-147, LLR +2.96 against bounds of ±2.94.
     *
     * <p>They are still only the first pair of numbers that worked. Nothing was tried against them —
     * a deeper cap or a wider margin might be better, and each alternative is its own match.
     */
    private static final int REVERSE_FUTILITY_MAX_DEPTH = 6;
    private static final int REVERSE_FUTILITY_MARGIN = 85;

    private static final int LMR_MIN_DEPTH = 3;
    /** The first few moves are the ones ordering believes in, and they get searched in full. */
    private static final int LMR_MIN_MOVES = 3;

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
            int score = negamax(board, depth, 0, -INFINITY, INFINITY, true);

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

    /**
     * @param allowNull false directly under a null move, so the search cannot pass twice running and
     *                  reason its way to a conclusion neither side could reach by playing chess
     */
    private int negamax(Board board, int depth, int ply, int alpha, int beta, boolean allowNull) {
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

        int us = board.sideToMove();
        boolean inCheck = board.inCheck();
        // A window of one point means the caller only wants to know which side of it the score falls
        // on. Guessing wrong there costs a re-search; guessing wrong on a real window corrupts the
        // move the engine plays, so speculation is confined to the narrow ones.
        boolean isPv = beta - alpha > 1;

        // Reverse futility pruning: if the position is already so far above beta that a whole search is
        // unlikely to drag it back down, take the static score and stop. This is the cheaper sibling of
        // null move pruning — it asks the same question without playing a move at all — so it goes
        // first, and every node it answers is a null-move search that never happens.
        //
        // The margin grows with remaining depth because depth is how much the score can still move.
        // Guarded the same way, and for the same reasons: only on narrow windows, never in check, and
        // never near mate scores, where a linear margin in centipawns describes nothing.
        if (!isPv && !inCheck && depth <= REVERSE_FUTILITY_MAX_DEPTH
                && Math.abs(beta) < MATE_THRESHOLD) {
            int staticScore = evaluator.evaluate(board);
            if (staticScore - REVERSE_FUTILITY_MARGIN * depth >= beta) {
                return staticScore;
            }
        }

        // Null move pruning: hand the opponent a free move, and if the position is still good enough
        // to fail high at reduced depth, it was never worth searching properly. Almost any real move
        // beats doing nothing, so surviving a free move is strong evidence.
        //
        // Three guards, each protecting against a way it lies. Not in check, because passing the turn
        // there is not merely bad but illegal. Not without pieces, because king and pawn endings run
        // on zugzwang, where being forced to move is the disadvantage and "doing nothing is bad for
        // me" stops holding. And not twice in a row, or the search reasons about a line neither side
        // could ever play.
        if (allowNull && !isPv && !inCheck && depth >= NULL_MOVE_MIN_DEPTH
                && board.hasNonPawnMaterial(us) && Math.abs(beta) < MATE_THRESHOLD) {
            int reduction = NULL_MOVE_BASE_REDUCTION + depth / 6;
            board.makeNullMove();
            int score = -negamax(board, depth - 1 - reduction, ply + 1, -beta, -beta + 1, false);
            board.unmakeNullMove();

            if (aborted) {
                return DRAW;
            }
            if (score >= beta) {
                // Never propagate a mate found beyond a null move: the move that produced it is one
                // nobody can play, so the mate is not real. Returning beta keeps the cutoff without
                // claiming a forced win that does not exist.
                return isMateScore(score) ? beta : score;
            }
        }

        int originalAlpha = alpha;
        int[] moves = moveLists[ply];
        int count = MoveGenerator.generate(board, moves);
        order(board, moves, orderingScores[ply], count, ply, tableMove);

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
            boolean givesCheck = board.inCheck();

            // Principal variation search. The ordering has already done its work, so the first move
            // is usually the best one; every move after it is far more likely to be refuted than to
            // beat it. So they get a scout — a one-point window that asks only "does this beat
            // alpha?" and answers far faster than a real search — and only a move that says yes is
            // searched properly.
            //
            // This is also what makes reductions possible at all. A null window is the only place
            // speculation is cheap: guessing wrong costs a re-search, whereas guessing wrong on a
            // full window would corrupt the score the engine acts on. Null move pruning was measured
            // at nothing before this existed, because its own guard correctly refused to fire on
            // wide windows and there were none of any other kind.
            int score;
            if (legalMoves == 1) {
                score = -negamax(board, depth - 1, ply + 1, -beta, -alpha, true);
            } else {
                // Late move reduction. If ordering is any good, a quiet move sitting eighth in the
                // list is not the best move, and searching it to full depth is work spent confirming
                // something already likely. Search it shallower; if the shallow search is wrong and
                // the move does beat alpha, the mistake is caught and paid for immediately.
                //
                // Captures, promotions, checks and evasions are exempt: those are the moves that
                // change the position sharply, which is exactly where a missed line hurts.
                int reduction = reductionFor(depth, legalMoves, isPv, inCheck, givesCheck, move);

                score = -negamax(board, depth - 1 - reduction, ply + 1, -alpha - 1, -alpha, true);

                if (reduction > 0 && score > alpha) {
                    // The reduction was wrong about this move. Re-scout at full depth before
                    // committing to a full-window search.
                    score = -negamax(board, depth - 1, ply + 1, -alpha - 1, -alpha, true);
                }
                if (score > alpha && score < beta) {
                    score = -negamax(board, depth - 1, ply + 1, -beta, -alpha, true);
                }
            }

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
            return inCheck ? -MATE + ply : DRAW;
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

    /**
     * How many plies to shave off a late quiet move.
     *
     * <p>Zero for anything sharp — captures, promotions, checks given or received — because those
     * are the moves where a line missed is a line that loses material or the game. Zero, too, for
     * the moves ordering ranked highest, which are the ones it has a real opinion about.
     *
     * <p>A principal variation node reduces one ply less. The score there is the one the engine will
     * act on, so speculation is worth less and a mistake costs more.
     */
    private static int reductionFor(int depth, int moveNumber, boolean isPv, boolean inCheck,
                                    boolean givesCheck, int move) {
        if (depth < LMR_MIN_DEPTH || moveNumber <= LMR_MIN_MOVES
                || inCheck || givesCheck
                || Move.isCapture(move) || Move.isPromotion(move)) {
            return 0;
        }
        int reduction = 1;
        if (depth >= 6 && moveNumber >= 6) {
            reduction++;
        }
        if (isPv) {
            reduction--;
        }
        // Never reduce into quiescence: a move dropped straight to the horizon is not reduced, it is
        // unexamined.
        return Math.max(0, Math.min(reduction, depth - 2));
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
