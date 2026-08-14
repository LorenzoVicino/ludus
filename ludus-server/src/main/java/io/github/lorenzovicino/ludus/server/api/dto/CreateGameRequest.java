package io.github.lorenzovicino.ludus.server.api.dto;

import io.github.lorenzovicino.ludus.server.domain.Difficulty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * @param difficulty  how hard the engine tries. Defaults to {@link Difficulty#CLUB}
 * @param playAsWhite whether the human takes white. Defaults to true
 * @param startFen    an optional position to start from, for setting up a puzzle or an ending. Omit for
 *                    a normal game
 */
@Schema(description = "Settings for a new game. Every field has a sensible default.")
public record CreateGameRequest(
        Difficulty difficulty,

        Boolean playAsWhite,

        @Size(max = 100, message = "A FEN is never this long")
        String startFen) {

    public Difficulty difficultyOrDefault() {
        return difficulty == null ? Difficulty.CLUB : difficulty;
    }

    public boolean playAsWhiteOrDefault() {
        return playAsWhite == null || playAsWhite;
    }
}
