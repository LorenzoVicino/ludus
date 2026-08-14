package io.github.lorenzovicino.ludus.server.api;

import io.github.lorenzovicino.ludus.server.domain.Game;
import io.github.lorenzovicino.ludus.server.engine.EngineService;
import io.github.lorenzovicino.ludus.server.service.GameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The engine thinking out loud, streamed as it goes.
 *
 * <h2>Why this endpoint exists at all</h2>
 *
 * <p>Iterative deepening means the engine finishes depth 1, then depth 2, and so on, having a complete
 * answer at every step and improving it. A request that waits for the end throws away everything in
 * between — and what is in between is the interesting part: watching the evaluation swing and the
 * principal variation get rewritten as it sees further is the clearest demonstration that a search is
 * happening rather than a lookup.
 *
 * <h2>Why it needed almost no code</h2>
 *
 * <p>The engine has had {@code SearchListener} since the first milestone, to emit the {@code info} lines
 * UCI requires. Streaming to a browser is the same event on a different transport, so this endpoint
 * subscribes to a callback that already existed for another reason and forwards it. No engine code
 * changed to make this possible — which is the practical payoff of having built the seam early.
 *
 * <p>Read-only: it analyses the current position and does not play the move. Playing is
 * {@code POST /moves}, and an endpoint that answered a {@code GET} by changing the game would be lying
 * about its method.
 */
@RestController
@RequestMapping("/api/games")
@Tag(name = "Analysis", description = "Watch the search, one iteration at a time")
public class AnalysisController {

    private static final Logger log = LoggerFactory.getLogger(AnalysisController.class);

    /** Long enough for the deepest allowed search, short enough that a dead client is reaped. */
    private static final long TIMEOUT_MILLIS = 30_000;

    private final GameService games;
    private final EngineService engine;
    private final Executor executor;

    public AnalysisController(GameService games, EngineService engine, Executor analysisExecutor) {
        this.games = games;
        this.engine = engine;
        this.executor = analysisExecutor;
    }

    @GetMapping(value = "/{id}/analysis", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream the search on the current position",
            description = "Server-sent events: one `iteration` event per completed depth, then `best`.")
    public SseEmitter analyse(@PathVariable UUID id,
                              @RequestParam(defaultValue = "10") int depth) {

        // Read the game on the request thread, so a missing id is a clean 404 rather than an error
        // arriving inside a stream the client has already been told is fine.
        Game game = games.find(id);
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);

        executor.execute(() -> {
            try {
                EngineService.EngineMove best = engine.analyse(game.board(), depth, iteration -> {
                    try {
                        emitter.send(SseEmitter.event().name("iteration").data(iteration));
                    } catch (IOException | IllegalStateException closed) {
                        // The client has gone. Not worth a stack trace, and not worth stopping the
                        // search for either — it is already borrowed and will be back in milliseconds.
                        log.debug("analysis stream for {} closed early", id);
                    }
                });
                emitter.send(SseEmitter.event().name("best").data(best));
                emitter.complete();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emitter.completeWithError(e);
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
