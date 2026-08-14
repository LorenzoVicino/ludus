package io.github.lorenzovicino.ludus.server.service;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Pieces;
import io.github.lorenzovicino.ludus.server.domain.Difficulty;
import io.github.lorenzovicino.ludus.server.domain.Game;
import io.github.lorenzovicino.ludus.server.domain.GameNotFoundException;
import io.github.lorenzovicino.ludus.server.domain.GameStatus;
import io.github.lorenzovicino.ludus.server.domain.MoveCodec;
import io.github.lorenzovicino.ludus.server.domain.Rules;
import io.github.lorenzovicino.ludus.server.engine.EngineService;
import io.github.lorenzovicino.ludus.server.persistence.GameRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Playing a game: the rules, the storage and the engine, in one place.
 *
 * <h2>Why the write methods are not {@code @Transactional}</h2>
 *
 * <p>Because a search takes seconds, and <strong>a database transaction must not be held open across a
 * multi-second CPU-bound computation.</strong> A connection is a scarce, pooled resource; parking one
 * while a core grinds through a search tree wastes it, and under load the connection pool runs dry
 * before the engine pool does — which looks like a database problem and is not one.
 *
 * <p>So the sequence is: read the game, validate and apply the human's move in memory, <em>let go of
 * everything</em>, search, then write once. The entity is detached while the engine thinks.
 *
 * <p>Correctness across that gap comes from the {@code @Version} column rather than from a lock. If
 * another request moved in the meantime, the version has advanced and the write fails with an optimistic
 * locking failure, which the API turns into 409. That is the honest answer: the move was computed for a
 * position that no longer exists.
 */
@Service
public class GameService {

    private final GameRepository games;
    private final EngineService engine;
    private final Clock clock;

    public GameService(GameRepository games, EngineService engine, Clock clock) {
        this.games = games;
        this.engine = engine;
        this.clock = clock;
    }

    public Game create(Difficulty difficulty, boolean humanIsWhite, String startFen)
            throws InterruptedException {
        String fen = startFen == null || startFen.isBlank() ? Board.START_FEN : startFen.trim();
        // Rejects a malformed FEN here rather than on the first move, when it would look like a bug in
        // the move endpoint instead of a bad request to this one.
        Board board = Board.fromFen(fen);

        Instant now = clock.instant();
        Game game = save(new Game(UUID.randomUUID(), fen, difficulty, humanIsWhite, now));

        boolean engineToMove = board.sideToMove() == (humanIsWhite ? Pieces.BLACK : Pieces.WHITE);
        if (engineToMove) {
            game = playEngineMove(game, board);
        }
        return game;
    }

    public Game find(UUID id) {
        return games.findById(id).orElseThrow(() -> new GameNotFoundException(id));
    }

    public List<Game> recent(int limit) {
        return games.findRecent(PageRequest.of(0, Math.min(limit, 100)));
    }

    /**
     * Plays the human's move, then the engine's reply.
     *
     * @return the game after both moves, and what the engine answered
     */
    public PlayResult play(UUID id, String uci) throws InterruptedException {
        Game game = find(id);
        if (game.status().isOver()) {
            throw new GameOverException(game.status());
        }

        Board board = game.board();
        boolean humansTurn = board.sideToMove() == (game.humanIsWhite() ? Pieces.WHITE : Pieces.BLACK);
        if (!humansTurn) {
            throw new NotYourTurnException();
        }

        // Throws IllegalMoveException, with the legal alternatives, if this is not one of them.
        int move = MoveCodec.parse(board, uci);
        board.makeMove(move);
        GameStatus afterHuman = Rules.statusOf(board);
        game.append(uci, afterHuman, clock.instant());

        if (afterHuman.isOver()) {
            return new PlayResult(save(game), null);
        }

        // No transaction is open here, and that is the point.
        EngineService.EngineMove reply = engine.chooseMove(board, game.difficulty());
        if (!reply.hasMove()) {
            // The search found no move in a position that has legal moves, which should be impossible.
            // Saving the human's move and reporting no reply beats inventing one.
            return new PlayResult(save(game), null);
        }

        board.makeMove(MoveCodec.parse(board, reply.move()));
        game.append(reply.move(), Rules.statusOf(board), clock.instant());
        return new PlayResult(save(game), reply);
    }

    public Game resign(UUID id) {
        Game game = find(id);
        if (!game.status().isOver()) {
            game.resign(clock.instant());
            return save(game);
        }
        return game;
    }

    private Game playEngineMove(Game game, Board board) throws InterruptedException {
        EngineService.EngineMove first = engine.chooseMove(board, game.difficulty());
        if (!first.hasMove()) {
            return game;
        }
        board.makeMove(MoveCodec.parse(board, first.move()));
        game.append(first.move(), Rules.statusOf(board), clock.instant());
        return save(game);
    }

    /**
     * The only write, and its transaction is the repository's own — one statement wide.
     *
     * <p>Deliberately <strong>not</strong> annotated {@code @Transactional}. Spring's transaction
     * support is proxy-based, so an annotation on a method this class calls on itself would be
     * silently ignored: the call never leaves the object and never passes the proxy. It is the classic
     * way to end up with code that reads as transactional and is not. Spring Data's repository methods
     * are already transactional individually, which is exactly the scope wanted here, so the correct
     * amount of annotation is none.
     */
    private Game save(Game game) {
        return games.saveAndFlush(game);
    }

    /** A game after both moves, with what the engine played, or {@code null} if it had no turn. */
    public record PlayResult(Game game, EngineService.EngineMove engineMove) {
    }

    /** The game has already finished. Answered with 409. */
    public static class GameOverException extends RuntimeException {
        public GameOverException(GameStatus status) {
            super("This game is over: " + status);
        }
    }

    /** It is the engine's turn, not yours. Answered with 409. */
    public static class NotYourTurnException extends RuntimeException {
        public NotYourTurnException() {
            super("It is not your turn to move");
        }
    }
}
