package io.github.lorenzovicino.ludus.server.api.dto;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Pieces;
import io.github.lorenzovicino.ludus.server.domain.Difficulty;
import io.github.lorenzovicino.ludus.server.domain.Game;
import io.github.lorenzovicino.ludus.server.domain.GameStatus;
import io.github.lorenzovicino.ludus.server.domain.MoveCodec;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A game as the browser sees it.
 *
 * <p>Deliberately not the entity. Two reasons, and the second is the one that matters: an entity
 * serialised straight out leaks the storage decisions — the version column, the derived FEN, whatever is
 * added next — into a contract clients then depend on. And it cannot carry {@code legalMoves} or
 * {@code yourTurn}, which are the two things a board needs and neither is stored.
 *
 * @param legalMoves every move allowed right now, so the browser can light up squares and reject a drag
 *                   without a round trip. It costs the server nothing: the move generator ran anyway
 * @param version    the value to send back in {@code If-Match} on the next move, which is how two fast
 *                   clicks stop being two moves
 */
@Schema(description = "The state of a game, including the moves that are legal right now.")
public record GameView(
        UUID id,
        String fen,
        GameStatus status,
        Difficulty difficulty,
        boolean youAreWhite,
        boolean yourTurn,
        boolean inCheck,
        int plies,
        List<String> moves,
        List<String> legalMoves,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static GameView of(Game game) {
        Board board = game.board();
        boolean whiteToMove = board.sideToMove() == Pieces.WHITE;
        boolean over = game.status().isOver();

        return new GameView(
                game.id(),
                board.toFen(),
                game.status(),
                game.difficulty(),
                game.humanIsWhite(),
                !over && whiteToMove == game.humanIsWhite(),
                board.inCheck(),
                game.plies(),
                game.moveList(),
                over ? List.of() : MoveCodec.legalMoves(board),
                game.createdAt(),
                game.updatedAt(),
                game.version());
    }
}
