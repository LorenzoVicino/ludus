package io.github.lorenzovicino.ludus.tools.selfplay;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import io.github.lorenzovicino.ludus.core.Pieces;
import io.github.lorenzovicino.ludus.eval.HandCraftedEvaluator;
import io.github.lorenzovicino.ludus.search.Search;
import io.github.lorenzovicino.ludus.search.SearchLimits;
import io.github.lorenzovicino.ludus.search.SearchResult;
import java.util.ArrayList;
import java.util.List;

/**
 * Plays games against itself and records positions worth learning from.
 *
 * <p>The search runs in-process rather than through UCI. Training needs millions of positions, and a
 * subprocess round trip per move would dominate the cost; running the search directly also hands us
 * its score, which is half of what a sample is.
 *
 * <h2>Which positions are kept</h2>
 *
 * <p>Most of them are thrown away, and the filtering matters more than the volume. A network trained
 * on everything learns to predict the noise as well as the signal.
 *
 * <ul>
 *   <li><strong>Not while in check.</strong> A static evaluation of a position under check describes
 *       something the network is not being asked to judge; the search handles those.</li>
 *   <li><strong>Not when the best move is a capture.</strong> The position is mid-exchange, so its
 *       static score is the illusion quiescence exists to dispel. Training on it teaches the network
 *       to believe the same illusion.</li>
 *   <li><strong>Not when the score is a mate.</strong> Mate is distance, not evaluation, and a label
 *       of thirty thousand centipawns drags every weight it touches.</li>
 *   <li><strong>Not the opening plies.</strong> They come from random moves, so they are a sample of
 *       the book rather than of chess.</li>
 * </ul>
 *
 * <p>Not thread-safe: one instance owns one search with its own tables.
 */
public final class SelfPlayGenerator {

    /** Deep enough for the score to mean something, shallow enough to produce data at volume. */
    public static final int DEFAULT_DEPTH = 6;

    private static final int MAX_PLIES = 300;
    /** Opening plies come from random moves and describe the book, not the game. */
    private static final int SKIP_OPENING_PLIES = 4;

    private final Search search = new Search(new HandCraftedEvaluator());
    private final int[] legal = new int[MoveGenerator.MAX_MOVES];

    /** Plays one game from {@code openingFen} and returns the positions worth keeping. */
    public List<SelfPlaySample> play(String openingFen, int depth) {
        Board board = Board.fromFen(openingFen);
        search.newGame();

        List<Pending> pending = new ArrayList<>();
        int outcome;
        int ply = 0;

        while (true) {
            int legalCount =
                    MoveGenerator.filterLegal(board, legal, MoveGenerator.generate(board, legal));
            if (legalCount == 0) {
                // Checkmate is a loss for whoever has to move; stalemate is a draw.
                outcome = board.inCheck() ? SelfPlaySample.LOSS : SelfPlaySample.DRAW;
                break;
            }
            if (board.isFiftyMoveDraw() || board.isRepetition() || ply >= MAX_PLIES) {
                outcome = SelfPlaySample.DRAW;
                break;
            }

            SearchResult result = search.search(board, SearchLimits.depth(depth));
            if (!result.hasMove()) {
                outcome = SelfPlaySample.DRAW;
                break;
            }

            if (ply >= SKIP_OPENING_PLIES && isQuiet(board, result)) {
                pending.add(new Pending(board.toFen(), result.score(), board.sideToMove()));
            }

            board.makeMove(result.bestMove());
            ply++;
        }

        // The game ended for whoever was to move at the end; every sample is labelled from the point
        // of view of whoever was to move in *that* position.
        int sideToMoveAtEnd = board.sideToMove();
        List<SelfPlaySample> samples = new ArrayList<>(pending.size());
        for (Pending position : pending) {
            int resultForPosition = position.sideToMove() == sideToMoveAtEnd
                    ? outcome
                    : SelfPlaySample.WIN - outcome;
            samples.add(new SelfPlaySample(position.fen(), position.score(), resultForPosition));
        }
        return samples;
    }

    private boolean isQuiet(Board board, SearchResult result) {
        if (board.inCheck() || Search.isMateScore(result.score())) {
            return false;
        }
        int best = result.bestMove();
        return !Move.isCapture(best) && !Move.isPromotion(best);
    }

    /** A position recorded before the game's outcome was known. */
    private record Pending(String fen, int score, int sideToMove) {

        Pending {
            if (sideToMove != Pieces.WHITE && sideToMove != Pieces.BLACK) {
                throw new IllegalArgumentException("Not a colour: " + sideToMove);
            }
        }
    }
}
