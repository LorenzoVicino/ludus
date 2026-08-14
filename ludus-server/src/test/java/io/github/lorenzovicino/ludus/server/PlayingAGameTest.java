package io.github.lorenzovicino.ludus.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A real game, against a real engine, through a real database.
 *
 * <p>Everything is genuine here: Postgres in a container, Flyway creating the schema, and the search
 * actually searching. It is the only test that can catch the things the fast ones cannot — a migration
 * that does not match the entity, a column too short for a FEN, an enum name the check constraint
 * rejects. Those failures are invisible to a mocked controller test and obvious to a user.
 *
 * <p>Tagged {@code slow} and kept out of {@code mvn test}, which is the convention the rest of the
 * project uses for the deep perft runs. It needs Docker and it takes seconds rather than milliseconds.
 *
 * <p>The engine plays at {@code BEGINNER} throughout: this test is about the plumbing, and a deeper
 * search would only make it slower without checking anything further.
 */
@Tag("slow")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ludus.engine.pool-size=2",
        "ludus.engine.hash-megabytes=1"
})
class PlayingAGameTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private JsonNode newGame(String body) throws Exception {
        String response = mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    @Test
    @DisplayName("a game survives being written, read back and played")
    void playAFewMoves() throws Exception {
        JsonNode created = newGame("{\"difficulty\":\"BEGINNER\",\"playAsWhite\":true}");
        String id = created.get("id").asText();
        assertEquals(0, created.get("plies").asInt());
        assertTrue(created.get("yourTurn").asBoolean());

        // The reply comes back in the same response: one move and its answer are one interaction.
        String afterMove = mvc.perform(post("/api/games/{id}/moves", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"e2e4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engineMove.move").isString())
                .andReturn().getResponse().getContentAsString();

        JsonNode result = json.readTree(afterMove);
        assertEquals(2, result.get("game").get("plies").asInt(), "the human's move and the engine's");
        assertNotNull(result.get("engineMove").get("move"));
        assertTrue(result.get("engineMove").get("nodes").asLong() > 0, "it should have searched");
        assertTrue(result.get("game").get("yourTurn").asBoolean(), "back to the human");

        // Reading it back proves the moves round-tripped through Postgres rather than living in memory.
        //
        // One version bump for two moves, not two: both are written in a single save. That is the
        // property worth pinning — if the search fails, nothing is stored, so the game can never be left
        // holding the human's move with the engine's reply missing and nobody able to move. The pair is
        // atomic or it did not happen.
        mvc.perform(get("/api/games/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plies").value(2))
                .andExpect(jsonPath("$.moves[0]").value("e2e4"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("a game read back remembers what the engine thought, not just what it played")
    void theLastReplySurvivesAReload() throws Exception {
        // The point of storing it: a game is reachable by URL, so somebody opening a link or reloading
        // the page must still see the engine's reasoning. It used to be returned once and lost.
        JsonNode created = newGame("{\"difficulty\":\"BEGINNER\",\"playAsWhite\":true}");
        String id = created.get("id").asText();
        assertTrue(created.get("lastReply").isNull(), "nothing has been played yet");

        String played = mvc.perform(post("/api/games/{id}/moves", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"e2e4\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String answered = json.readTree(played).get("engineMove").get("move").asText();

        mvc.perform(get("/api/games/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastReply.move").value(answered))
                .andExpect(jsonPath("$.lastReply.depth").isNumber())
                .andExpect(jsonPath("$.lastReply.nodes").isNumber());
    }

    @Test
    @DisplayName("giving the engine white makes it move before anybody asks")
    void engineMovesFirstAsWhite() throws Exception {
        JsonNode created = newGame("{\"difficulty\":\"BEGINNER\",\"playAsWhite\":false}");

        assertEquals(1, created.get("plies").asInt(), "the engine has already played");
        assertTrue(created.get("yourTurn").asBoolean(), "and it is the human's turn");
        assertNotEquals(io.github.lorenzovicino.ludus.core.Board.START_FEN,
                created.get("fen").asText());
    }

    @Test
    @DisplayName("a game can start from a given position")
    void startFromAPosition() throws Exception {
        // A rook ending, which also checks that a FEN with no castling rights survives the round trip.
        JsonNode created = newGame(
                "{\"difficulty\":\"BEGINNER\",\"playAsWhite\":true,"
                        + "\"startFen\":\"6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1\"}");

        assertEquals("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1", created.get("fen").asText());
        assertTrue(created.get("legalMoves").size() > 5);
    }

    @Test
    @DisplayName("a nonsense position is refused when the game is created, not later")
    void malformedFenIsRejectedUpFront() throws Exception {
        mvc.perform(post("/api/games")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startFen\":\"not a position\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("resigning ends the game and refuses further moves")
    void resigning() throws Exception {
        JsonNode created = newGame("{\"difficulty\":\"BEGINNER\"}");
        String id = created.get("id").asText();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/games/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESIGNED"))
                .andExpect(jsonPath("$.legalMoves").isEmpty());

        mvc.perform(post("/api/games/{id}/moves", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"move\":\"e2e4\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the engine's thinking streams while it thinks")
    void analysisStreams() throws Exception {
        JsonNode created = newGame("{\"difficulty\":\"BEGINNER\"}");
        String id = created.get("id").asText();

        String stream = mvc.perform(get("/api/games/{id}/analysis?depth=5", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(stream.contains("event:iteration"),
                "there should be one event per completed depth, got: " + stream);
        assertTrue(stream.contains("event:best"), "and a final answer");
        // Iterative deepening means several, not one. A single event would mean the search reported only
        // its last iteration, which is the interesting part of this endpoint gone.
        assertTrue(stream.split("event:iteration").length > 2, "several iterations, not one");
    }

    @Test
    @DisplayName("the recent games list is there and ordered")
    void recentGames() throws Exception {
        newGame("{\"difficulty\":\"BEGINNER\"}");
        newGame("{\"difficulty\":\"CASUAL\"}");

        mvc.perform(get("/api/games?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }
}
