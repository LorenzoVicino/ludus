package io.github.lorenzovicino.ludus.eval;

import io.github.lorenzovicino.ludus.core.Board;

/**
 * Scores a position. The seam that makes Act II an implementation rather than a rewrite.
 *
 * <p>The three default methods are the whole point of this interface existing now, before there is
 * anything to plug into it. {@link HandCraftedEvaluator} ignores them — it holds no state and
 * derives everything from the position in front of it. An NNUE cannot afford that: recomputing its
 * first layer at every node is precisely what it is designed to avoid, so it needs to hear about
 * each move as it is made and unmade in order to keep its accumulator up to date incrementally.
 *
 * <p>Without these hooks, adding the network later would mean editing {@code makeMove} and every
 * branch of the search. With them, the search stays ignorant and the composition root picks an
 * implementation. See DESIGN.md §6.
 *
 * <h2>When the hooks fire</h2>
 *
 * <p>Both are called with the board in its <em>pre-move</em> position — {@code beforeMakeMove} just
 * before the move is applied, {@code afterUnmakeMove} just after it has been undone.
 *
 * <p>This corrects the contract sketched in the design document, which had the make hook firing
 * <em>after</em> the board was updated. That does not work: once the move is applied, the captured
 * piece is gone from the board, and its identity and square are exactly what a feature delta needs.
 * Reading the position before the move makes every piece involved — mover, victim, castling rook —
 * plainly visible, and the method names carry the contract so nobody has to remember it.
 *
 * <p>The search calls the hooks around <em>every</em> move it tries, including ones that turn out to
 * be illegal and are immediately unmade. The pairing is therefore always balanced, which is what an
 * implementation pushing and popping a stack relies on.
 */
public interface Evaluator {

    /**
     * Score in centipawns from the point of view of the side to move: positive means the side to
     * move is better off. Negamax requires this orientation.
     */
    int evaluate(Board board);

    /** Called with {@code board} in its pre-move position, before {@code move} is applied. */
    default void beforeMakeMove(Board board, int move) {
    }

    /** Called with {@code board} back in its pre-move position, after {@code move} was undone. */
    default void afterUnmakeMove(Board board, int move) {
    }

    /**
     * Discards any incremental state and re-derives it from {@code board}.
     *
     * <p>The search calls this before each new search, because a UCI host can hand it a position
     * unrelated to the last one. A stateless evaluator has nothing to do here.
     */
    default void reset(Board board) {
    }
}
