package io.github.lorenzovicino.ludus.server.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * @param move the move in UCI notation: from-square, to-square, and a promotion piece if the move is
 *             one — {@code e2e4}, {@code e1g1} for castling, {@code e7e8q} for a promotion
 */
@Schema(description = "One move, in UCI notation.")
public record MoveRequest(

        // Shape only. Whether this particular move is legal in this particular position is not a thing
        // a regular expression can know, and the answer comes from the move generator instead.
        @NotBlank(message = "A move is required")
        @Pattern(regexp = "^[a-h][1-8][a-h][1-8][qrbn]?$",
                message = "Not UCI notation: expected something like e2e4, or e7e8q for a promotion")
        String move) {
}
