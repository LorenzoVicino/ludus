package io.github.lorenzovicino.ludus.server.domain;

import java.util.List;

/**
 * The move offered is not legal here.
 *
 * <p>Carries the legal alternatives, because the engine already generated them in order to decide that
 * — so the response can say what <em>was</em> allowed at no extra cost. An error that only says "no"
 * makes the client guess.
 */
public class IllegalMoveException extends RuntimeException {

    private final String offered;
    private final List<String> legalMoves;

    public IllegalMoveException(String offered, List<String> legalMoves) {
        super("Not a legal move in this position: " + offered);
        this.offered = offered;
        this.legalMoves = List.copyOf(legalMoves);
    }

    public String offered() {
        return offered;
    }

    public List<String> legalMoves() {
        return legalMoves;
    }
}
