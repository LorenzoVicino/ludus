package io.github.lorenzovicino.ludus.server.api.dto;

import io.github.lorenzovicino.ludus.server.engine.EngineService;
import io.github.lorenzovicino.ludus.server.service.GameService;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The answer to a move: the new state, and what the engine played back.
 *
 * @param engineMove null when the human's move ended the game, so there was nothing to reply with
 */
@Schema(description = "The game after your move and the engine's reply.")
public record MoveResponse(GameView game, EngineReply engineMove) {

    public static MoveResponse of(GameService.PlayResult result) {
        return new MoveResponse(GameView.of(result.game()), EngineReply.of(result.engineMove()));
    }

    /**
     * @param score in centipawns, from the engine's point of view when it moved. Positive means it
     *              thinks it is better
     * @param depth how many plies it managed within its allowance, which is the honest measure of how
     *              hard it tried — the level asks for a depth and the clock may cut it short
     */
    @Schema(description = "What the engine played, and what it thought while playing it.")
    public record EngineReply(String move, int score, int depth, long nodes) {

        static EngineReply of(EngineService.EngineMove move) {
            return move == null || !move.hasMove()
                    ? null
                    : new EngineReply(move.move(), move.score(), move.depth(), move.nodes());
        }
    }
}
