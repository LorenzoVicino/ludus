package io.github.lorenzovicino.ludus.server.domain;

/** How a game stands. Anything other than {@link #IN_PROGRESS} refuses further moves. */
public enum GameStatus {

    IN_PROGRESS,
    WHITE_WON,
    BLACK_WON,
    DRAW_STALEMATE,
    DRAW_FIFTY_MOVE,
    DRAW_REPETITION,
    DRAW_INSUFFICIENT_MATERIAL,
    RESIGNED;

    public boolean isOver() {
        return this != IN_PROGRESS;
    }
}
