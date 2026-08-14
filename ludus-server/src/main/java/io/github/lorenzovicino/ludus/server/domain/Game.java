package io.github.lorenzovicino.ludus.server.domain;

import io.github.lorenzovicino.ludus.core.Board;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A game in progress, as it is stored.
 *
 * <h2>What is persisted, and what is not</h2>
 *
 * <p>The starting position and <strong>the list of moves</strong> — not the current position. The
 * current position is derived by replaying them, which costs microseconds and is the only way to get
 * two rules right: a draw by repetition needs to know which positions have occurred, and the fifty-move
 * counter needs the history that produced it. A stored FEN answers "where are the pieces" and forgets
 * both.
 *
 * <p>The FEN column exists anyway, written on every save. It is a <em>derived</em> convenience for
 * reading the table by hand and for the API's response, never a source of truth — {@link #board()}
 * always replays.
 *
 * <h2>Why there is a version column</h2>
 *
 * <p>Two clicks arriving together must not both play a move. With {@code @Version}, the second write
 * fails rather than silently overwriting the first, and the API turns that into {@code 409 Conflict}.
 * A browser with an impatient user is not a hypothetical concurrent client.
 */
@Entity
@Table(name = "games")
public class Game {

    @Id
    private UUID id;

    @Column(name = "start_fen", nullable = false, length = 100)
    private String startFen;

    /** Space-separated UCI moves, in order. Empty for a game nobody has moved in. */
    @Column(name = "moves", nullable = false, columnDefinition = "text")
    private String moves = "";

    /** Derived from the moves on every save. Convenience for reading, never authoritative. */
    @Column(name = "fen", nullable = false, length = 100)
    private String fen;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private GameStatus status = GameStatus.IN_PROGRESS;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 16)
    private Difficulty difficulty = Difficulty.CLUB;

    /** Which colour the human plays: {@code true} for white. */
    @Column(name = "human_is_white", nullable = false)
    private boolean humanIsWhite = true;

    /**
     * What the engine thought about the move it last played, or null before it has played one.
     *
     * <p>Kept because a game is reachable by URL. Without it, reloading a game — or opening somebody's
     * link — leaves the panel that exists to show the engine's reasoning showing nothing, and the
     * information was already computed and thrown away.
     *
     * <p>Nullable rather than zero: "it has not answered yet" and "it answered nought centipawns" are
     * different facts, and nought is an ordinary score.
     */
    @Column(name = "last_move", length = 6)
    private String lastMove;

    @Column(name = "last_score")
    private Integer lastScore;

    @Column(name = "last_depth")
    private Integer lastDepth;

    @Column(name = "last_nodes")
    private Long lastNodes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Game() {
        // JPA
    }

    public Game(UUID id, String startFen, Difficulty difficulty, boolean humanIsWhite, Instant now) {
        this.id = id;
        this.startFen = startFen;
        this.fen = startFen;
        this.difficulty = difficulty;
        this.humanIsWhite = humanIsWhite;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * The current position, rebuilt from the starting position and the moves played.
     *
     * <p>The caller gets a fresh board every time, which is deliberate: a {@code Board} is mutable and
     * the search will push and pop moves all over it. Handing out a shared one would be a data race
     * waiting for a second request.
     */
    public Board board() {
        Board board = Board.fromFen(startFen);
        for (String uci : moveList()) {
            board.makeMove(MoveCodec.parse(board, uci));
        }
        return board;
    }

    public List<String> moveList() {
        if (moves.isBlank()) {
            return List.of();
        }
        return List.of(moves.trim().split(" "));
    }

    /** Appends a move and refreshes the derived columns. The move must already be known legal. */
    public void append(String uci, GameStatus newStatus, Instant now) {
        List<String> played = new ArrayList<>(moveList());
        played.add(uci);
        this.moves = String.join(" ", played);
        this.fen = board().toFen();
        this.status = newStatus;
        this.updatedAt = now;
    }

    /** Records what the engine thought about the move it just appended. */
    public void rememberReply(String move, int score, int depth, long nodes) {
        this.lastMove = move;
        this.lastScore = score;
        this.lastDepth = depth;
        this.lastNodes = nodes;
    }

    public String lastMove() {
        return lastMove;
    }

    public Integer lastScore() {
        return lastScore;
    }

    public Integer lastDepth() {
        return lastDepth;
    }

    public Long lastNodes() {
        return lastNodes;
    }

    public void resign(Instant now) {
        this.status = GameStatus.RESIGNED;
        this.updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String startFen() {
        return startFen;
    }

    public String fen() {
        return fen;
    }

    public GameStatus status() {
        return status;
    }

    public Difficulty difficulty() {
        return difficulty;
    }

    public boolean humanIsWhite() {
        return humanIsWhite;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }

    public int plies() {
        return moveList().size();
    }
}
