package io.github.lorenzovicino.ludus.server.api;

import io.github.lorenzovicino.ludus.server.api.dto.CreateGameRequest;
import io.github.lorenzovicino.ludus.server.api.dto.GameView;
import io.github.lorenzovicino.ludus.server.api.dto.MoveRequest;
import io.github.lorenzovicino.ludus.server.api.dto.MoveResponse;
import io.github.lorenzovicino.ludus.server.domain.Game;
import io.github.lorenzovicino.ludus.server.service.GameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Games as resources.
 *
 * <p>A game is a thing with an identity and a history, so it gets a URL and its moves are a
 * sub-collection you post to. The engine's reply comes back in the same response rather than needing a
 * second request, because from the client's point of view one move and its answer are one interaction.
 *
 * <p>Nothing here knows how the engine works, that engines are pooled, or that a search takes time. It
 * validates, delegates, and shapes the answer.
 */
@RestController
@RequestMapping("/api/games")
@Tag(name = "Games", description = "Start a game, make moves, watch the engine think")
public class GameController {

    private final GameService games;

    public GameController(GameService games) {
        this.games = games;
    }

    @PostMapping
    @Operation(summary = "Start a game",
            description = "Returns 201 with the game's location. If the engine has white, it has "
                    + "already moved by the time this returns.")
    public ResponseEntity<GameView> create(@Valid @RequestBody(required = false) CreateGameRequest request)
            throws InterruptedException {
        CreateGameRequest settings = request == null
                ? new CreateGameRequest(null, null, null)
                : request;

        Game game = games.create(settings.difficultyOrDefault(), settings.playAsWhiteOrDefault(),
                settings.startFen());

        // 201 with a Location header, because the client did not choose the id and needs to be told it.
        return ResponseEntity.created(URI.create("/api/games/" + game.id()))
                .eTag(etagOf(game))
                .body(GameView.of(game));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Read a game, with the moves that are legal right now")
    public ResponseEntity<GameView> read(@PathVariable UUID id) {
        Game game = games.find(id);
        return ResponseEntity.ok().eTag(etagOf(game)).body(GameView.of(game));
    }

    @GetMapping
    @Operation(summary = "Recent games, most recently played first")
    public List<GameView> recent(@RequestParam(defaultValue = "20") int limit) {
        return games.recent(limit).stream().map(GameView::of).toList();
    }

    /**
     * Plays a move and returns the engine's reply.
     *
     * <p>{@code If-Match} is optional and worth sending. A browser where somebody clicks twice quickly
     * is a concurrent client, and without it the second click plays a second move from a board the user
     * never saw. With it, the second request is refused with 412 and the client can re-read.
     *
     * <p>The version is also checked at the database on write, so a client that omits the header is not
     * unprotected — it just finds out later, as a 409 instead of a 412.
     */
    @PostMapping("/{id}/moves")
    @Operation(summary = "Play a move",
            description = "Send If-Match with the version you last read to be sure you are moving "
                    + "from the position you are looking at.")
    public ResponseEntity<MoveResponse> move(
            @PathVariable UUID id,
            @Valid @RequestBody MoveRequest request,
            @RequestHeader(value = "If-Match", required = false) String ifMatch)
            throws InterruptedException {

        if (ifMatch != null && !ifMatch.isBlank()) {
            Game current = games.find(id);
            if (!etagOf(current).equals(normalise(ifMatch))) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                        "The game has moved on since you read it; re-read and try again");
            }
        }

        MoveResponse response = MoveResponse.of(games.play(id, request.move()));
        return ResponseEntity.ok().eTag("\"" + response.game().version() + "\"").body(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Resign",
            description = "The game is kept rather than deleted; its status becomes RESIGNED.")
    public GameView resign(@PathVariable UUID id) {
        return GameView.of(games.resign(id));
    }

    /** The entity's version is exactly what an ETag is for: a cheap, correct token of "which state". */
    private static String etagOf(Game game) {
        return "\"" + game.version() + "\"";
    }

    private static String normalise(String header) {
        String trimmed = header.trim();
        return trimmed.startsWith("W/") ? trimmed.substring(2) : trimmed;
    }
}
