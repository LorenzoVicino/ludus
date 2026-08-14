package io.github.lorenzovicino.ludus.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.server.domain.Difficulty;
import io.github.lorenzovicino.ludus.server.domain.Game;
import io.github.lorenzovicino.ludus.server.domain.GameNotFoundException;
import io.github.lorenzovicino.ludus.server.domain.IllegalMoveException;
import io.github.lorenzovicino.ludus.server.engine.EngineBusyException;
import io.github.lorenzovicino.ludus.server.engine.EngineService;
import io.github.lorenzovicino.ludus.server.service.GameService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract, with the service mocked out.
 *
 * <p>Deliberately not an end-to-end test: what is under examination here is status codes, headers and
 * the shape of the body — decisions that live in the controller and the exception handler. Wiring a real
 * engine and database in would make these assertions slower without making them stronger, and would mean
 * a failing status-code test could be caused by a database.
 *
 * <p>The error cases carry most of the value. A working request is one path; the ways a client can be
 * told "no" are five, and each one is a promise about what that client should do next.
 */
@WebMvcTest(controllers = GameController.class)
class GameControllerTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private GameService games;

    private static Game aGame() {
        return new Game(ID, Board.START_FEN, Difficulty.CLUB, true, Instant.parse("2026-08-10T12:00:00Z"));
    }

    @Test
    @DisplayName("creating a game answers 201 with where to find it")
    void createReturnsLocation() throws Exception {
        when(games.create(any(), anyBoolean(), any())).thenReturn(aGame());

        mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"difficulty\":\"CLUB\",\"playAsWhite\":true}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/games/" + ID))
                .andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.yourTurn").value(true))
                .andExpect(jsonPath("$.legalMoves.length()").value(20));
    }

    @Test
    @DisplayName("an empty body is accepted, because every setting has a default")
    void createWithoutABody() throws Exception {
        when(games.create(eq(Difficulty.CLUB), eq(true), any())).thenReturn(aGame());

        mvc.perform(post("/api/games"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("reading a game carries an ETag to move against")
    void readCarriesAnEtag() throws Exception {
        when(games.find(ID)).thenReturn(aGame());

        mvc.perform(get("/api/games/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.fen").value(Board.START_FEN));
    }

    @Test
    @DisplayName("an unknown game is 404, as a problem document")
    void unknownGame() throws Exception {
        when(games.find(ID)).thenThrow(new GameNotFoundException(ID));

        mvc.perform(get("/api/games/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Game not found"))
                .andExpect(jsonPath("$.type").value(
                        "https://github.com/LorenzoVicino/ludus/problems/game-not-found"));
    }

    @Test
    @DisplayName("a move that is not UCI at all is 400 before any chess happens")
    void malformedMoveIsRejectedByValidation() throws Exception {
        mvc.perform(post("/api/games/{id}/moves", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"knight to f3\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    @DisplayName("a well-formed but illegal move is 422 and says what was legal")
    void illegalMoveIsUnprocessable() throws Exception {
        when(games.play(eq(ID), eq("e2e5")))
                .thenThrow(new IllegalMoveException("e2e5", List.of("e2e3", "e2e4")));

        mvc.perform(post("/api/games/{id}/moves", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"e2e5\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.offered").value("e2e5"))
                .andExpect(jsonPath("$.legalMoves").isArray())
                .andExpect(jsonPath("$.legalMoves[1]").value("e2e4"));
    }

    @Test
    @DisplayName("moving from a stale version is refused with 412")
    void staleIfMatchIsPreconditionFailed() throws Exception {
        Game game = aGame();
        when(games.find(ID)).thenReturn(game);

        mvc.perform(post("/api/games/{id}/moves", ID)
                        .header("If-Match", "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"e2e4\"}"))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    @DisplayName("losing the race at the database is 409")
    void concurrentUpdateIsConflict() throws Exception {
        when(games.play(eq(ID), any())).thenThrow(new OptimisticLockingFailureException("stale"));

        mvc.perform(post("/api/games/{id}/moves", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"e2e4\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Concurrent update"));
    }

    @Test
    @DisplayName("no free engine is 503 with a Retry-After, not a 500")
    void busyEngineIsUnavailable() throws Exception {
        when(games.play(eq(ID), any())).thenThrow(new EngineBusyException(Duration.ofSeconds(10)));

        mvc.perform(post("/api/games/{id}/moves", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"e2e4\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "10"))
                .andExpect(jsonPath("$.title").value("Engine busy"));
    }

    @Test
    @DisplayName("playing when it is not your turn is 409")
    void notYourTurn() throws Exception {
        when(games.play(eq(ID), any())).thenThrow(new GameService.NotYourTurnException());

        mvc.perform(post("/api/games/{id}/moves", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"e2e4\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Not your turn"));
    }

    @Test
    @DisplayName("a successful move returns the new state and the engine's reply")
    void successfulMove() throws Exception {
        Game game = aGame();
        when(games.play(eq(ID), eq("e2e4"))).thenReturn(new GameService.PlayResult(
                game, new EngineService.EngineMove("e7e5", 12, 6, 45_000)));

        mvc.perform(post("/api/games/{id}/moves", ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"e2e4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engineMove.move").value("e7e5"))
                .andExpect(jsonPath("$.engineMove.score").value(12))
                .andExpect(jsonPath("$.engineMove.depth").value(6))
                .andExpect(jsonPath("$.game.id").value(ID.toString()));
    }
}
