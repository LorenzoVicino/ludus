package io.github.lorenzovicino.ludus.server.api;

import io.github.lorenzovicino.ludus.server.domain.GameNotFoundException;
import io.github.lorenzovicino.ludus.server.domain.IllegalMoveException;
import io.github.lorenzovicino.ludus.server.engine.EngineBusyException;
import io.github.lorenzovicino.ludus.server.service.GameService;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Failures, as answers a client can act on.
 *
 * <p>Everything here is {@link ProblemDetail} — RFC 9457 — so an error has a machine-readable type and a
 * shape that does not depend on which endpoint produced it. A JSON body invented per endpoint is a
 * contract nobody wrote down.
 *
 * <p>The rule applied throughout: <strong>say what was wrong and what would have worked.</strong> An
 * illegal move comes back with the legal moves, because the server generated them in order to decide
 * and throwing them away would make the client guess. A busy engine comes back with
 * {@code Retry-After}, because "try later" without a number is not information.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_PREFIX = "https://github.com/LorenzoVicino/ludus/problems/";

    @ExceptionHandler(GameNotFoundException.class)
    public ProblemDetail notFound(GameNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Game not found", e.getMessage(), "game-not-found");
    }

    /**
     * 422 rather than 400: the request was well-formed and understood, and the rules of chess are what
     * refused it. A 400 would tell the client to check its JSON.
     */
    @ExceptionHandler(IllegalMoveException.class)
    public ProblemDetail illegalMove(IllegalMoveException e) {
        ProblemDetail detail = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Illegal move",
                e.getMessage(), "illegal-move");
        detail.setProperty("offered", e.offered());
        detail.setProperty("legalMoves", e.legalMoves());
        return detail;
    }

    @ExceptionHandler(GameService.NotYourTurnException.class)
    public ProblemDetail notYourTurn(GameService.NotYourTurnException e) {
        return problem(HttpStatus.CONFLICT, "Not your turn", e.getMessage(), "not-your-turn");
    }

    @ExceptionHandler(GameService.GameOverException.class)
    public ProblemDetail gameOver(GameService.GameOverException e) {
        return problem(HttpStatus.CONFLICT, "Game over", e.getMessage(), "game-over");
    }

    /**
     * Somebody else moved between the read and the write. The move was computed for a position that no
     * longer exists, so there is nothing to do but say so.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail conflict(OptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "Concurrent update",
                "Another move was played while this one was being computed. Re-read the game and retry.",
                "concurrent-update");
    }

    /**
     * 503 with a {@code Retry-After}, not 500. The service is working correctly and is out of engines,
     * which is a different thing from broken and calls for a different reaction from the client.
     */
    @ExceptionHandler(EngineBusyException.class)
    public ResponseEntity<ProblemDetail> busy(EngineBusyException e) {
        ProblemDetail detail = problem(HttpStatus.SERVICE_UNAVAILABLE, "Engine busy",
                e.getMessage(), "engine-busy");
        long seconds = Math.max(1, e.waited().toSeconds());
        detail.setProperty("retryAfterSeconds", seconds);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(seconds))
                .body(detail);
    }

    /** A malformed FEN reaches here as an IllegalArgumentException from the board parser. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage(), "bad-request");
    }

    /**
     * Field validation, with the fields named.
     *
     * <p>An override rather than another {@code @ExceptionHandler}: the base class already claims this
     * exception type, and two handlers for one type in the same advice is an ambiguity Spring refuses to
     * start with.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status,
            WebRequest request) {

        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "Invalid request",
                "One or more fields are not acceptable", "validation-failed");
        detail.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList());
        return ResponseEntity.badRequest().body(detail);
    }

    /**
     * A search was interrupted, which means the server is shutting down while this request was waiting.
     * The interrupt is restored rather than swallowed, because a thread that loses its interrupt flag
     * stops responding to shutdown at all.
     */
    @ExceptionHandler(InterruptedException.class)
    public ProblemDetail interrupted(InterruptedException e) {
        Thread.currentThread().interrupt();
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted",
                "The server is shutting down. Retry.", "interrupted");
    }

    /**
     * The last resort, and the reason this class extends {@link ResponseEntityExceptionHandler}.
     *
     * <p>A catch-all on {@code Exception} in a {@code @RestControllerAdvice} silently outranks nothing
     * — Spring picks the most specific handler — but if the framework's own exceptions have no handler at
     * all, this one gets them. That turns a deliberate {@code ResponseStatusException(412)} into a 500,
     * and a malformed JSON body into a 500, and neither leaves a clue that a catch-all did it. Extending
     * the base class gives every framework exception a handler more specific than this one, so this stays
     * what it is meant to be: the answer for failures nobody anticipated.
     *
     * <p>Found by a test that expected 412 and got 500.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception e) {
        // The only place a stack trace is logged, and the only place the client is told nothing: an
        // unexpected failure has no useful detail to hand out and might carry internals if it did.
        log.error("Unhandled failure", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error",
                "Something went wrong on the server.", "internal-error");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String type) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(TYPE_PREFIX + type));
        return problem;
    }
}
