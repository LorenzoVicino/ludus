package io.github.lorenzovicino.ludus.core;

import java.util.Arrays;

/**
 * A chess position, mutated in place by {@link #makeMove(int)} and {@link #unmakeMove(int)}.
 *
 * <p>The board is deliberately mutable. Copying the position at every node would be simpler and
 * immune to a whole class of bug, but it allocates inside a loop that runs millions of times per
 * second — see DESIGN.md §3.3. What makes the mutable design safe is the invariant test: after
 * {@code makeMove} followed by {@code unmakeMove}, every field must be identical, and
 * {@link #stateSignature()} exists so a test can assert exactly that.
 *
 * <p>State is held twice over: as bitboards for set operations, and as a square array for
 * answering "what is on this square" without a scan. Keeping the two in sync is the price, and
 * again the invariant test is what collects it.
 */
public final class Board {

    /** Depth limit of the undo stack. Far above any real search or game length. */
    public static final int MAX_PLY = 1024;

    public static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    /**
     * Rights surviving a move touching each square. Applying it to both {@code from} and
     * {@code to} handles king moves, rook moves and rook captures in a single expression — a
     * rook captured on h8 clears black's kingside right just as a rook moving off h8 does.
     */
    private static final int[] CASTLING_MASK = new int[Squares.COUNT];

    static {
        Arrays.fill(CASTLING_MASK, Castling.ALL);
        CASTLING_MASK[Squares.E1] &= ~(Castling.WHITE_KING | Castling.WHITE_QUEEN);
        CASTLING_MASK[Squares.H1] &= ~Castling.WHITE_KING;
        CASTLING_MASK[Squares.A1] &= ~Castling.WHITE_QUEEN;
        CASTLING_MASK[Squares.E8] &= ~(Castling.BLACK_KING | Castling.BLACK_QUEEN);
        CASTLING_MASK[Squares.H8] &= ~Castling.BLACK_KING;
        CASTLING_MASK[Squares.A8] &= ~Castling.BLACK_QUEEN;
    }

    private final long[] byType = new long[Pieces.TYPE_COUNT];
    private final long[] byColor = new long[Pieces.COLOR_COUNT];
    private long occupied;
    private final int[] squares = new int[Squares.COUNT];

    private int sideToMove = Pieces.WHITE;
    private int castlingRights = Castling.NONE;
    private int epSquare = Squares.NONE;
    private int halfmoveClock;
    private int fullmoveNumber = 1;
    private long zobrist;

    private int ply;
    private final int[] undoCaptured = new int[MAX_PLY];
    private final int[] undoCastling = new int[MAX_PLY];
    private final int[] undoEp = new int[MAX_PLY];
    private final int[] undoHalfmove = new int[MAX_PLY];
    private final long[] undoZobrist = new long[MAX_PLY];

    private Board() {
        Arrays.fill(squares, Pieces.NO_PIECE);
    }

    public static Board startPosition() {
        return fromFen(START_FEN);
    }

    /**
     * Parses a FEN string. The halfmove clock and move number are optional — several published
     * test positions, Kiwipete among them, omit them.
     */
    public static Board fromFen(String fen) {
        String[] fields = fen.trim().split("\\s+");
        if (fields.length < 4) {
            throw new IllegalArgumentException("FEN needs at least 4 fields, got " + fields.length + ": " + fen);
        }

        Board board = new Board();
        String[] rows = fields[0].split("/", -1);
        if (rows.length != 8) {
            throw new IllegalArgumentException("FEN placement needs 8 ranks, got " + rows.length + ": " + fen);
        }
        for (int row = 0; row < 8; row++) {
            int rank = 7 - row;
            int file = 0;
            for (int i = 0; i < rows[row].length(); i++) {
                char c = rows[row].charAt(i);
                if (c >= '1' && c <= '8') {
                    file += c - '0';
                    continue;
                }
                int piece = Pieces.fromChar(c);
                if (piece == Pieces.NO_PIECE) {
                    throw new IllegalArgumentException("Bad piece '" + c + "' in FEN: " + fen);
                }
                if (file > 7) {
                    throw new IllegalArgumentException("Rank " + (rank + 1) + " overflows in FEN: " + fen);
                }
                board.putPiece(piece, Squares.of(file, rank));
                file++;
            }
            if (file != 8) {
                throw new IllegalArgumentException(
                        "Rank " + (rank + 1) + " describes " + file + " squares in FEN: " + fen);
            }
        }

        board.sideToMove = switch (fields[1]) {
            case "w" -> Pieces.WHITE;
            case "b" -> Pieces.BLACK;
            default -> throw new IllegalArgumentException("Bad side to move '" + fields[1] + "' in FEN: " + fen);
        };
        board.castlingRights = Castling.parse(fields[2]);
        board.epSquare = Squares.parse(fields[3]);
        board.halfmoveClock = fields.length > 4 ? Integer.parseInt(fields[4]) : 0;
        board.fullmoveNumber = fields.length > 5 ? Integer.parseInt(fields[5]) : 1;
        board.zobrist = board.recomputeZobrist();
        return board;
    }

    public String toFen() {
        StringBuilder out = new StringBuilder(90);
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int piece = squares[Squares.of(file, rank)];
                if (piece == Pieces.NO_PIECE) {
                    empty++;
                    continue;
                }
                if (empty > 0) {
                    out.append(empty);
                    empty = 0;
                }
                out.append(Pieces.toChar(piece));
            }
            if (empty > 0) {
                out.append(empty);
            }
            if (rank > 0) {
                out.append('/');
            }
        }
        out.append(' ').append(sideToMove == Pieces.WHITE ? 'w' : 'b');
        out.append(' ').append(Castling.toFen(castlingRights));
        out.append(' ').append(Squares.name(epSquare));
        out.append(' ').append(halfmoveClock);
        out.append(' ').append(fullmoveNumber);
        return out.toString();
    }

    public int sideToMove() {
        return sideToMove;
    }

    public int castlingRights() {
        return castlingRights;
    }

    public int epSquare() {
        return epSquare;
    }

    public int halfmoveClock() {
        return halfmoveClock;
    }

    public int fullmoveNumber() {
        return fullmoveNumber;
    }

    public long zobrist() {
        return zobrist;
    }

    public int ply() {
        return ply;
    }

    public long occupied() {
        return occupied;
    }

    public long empty() {
        return ~occupied;
    }

    public long byColor(int color) {
        return byColor[color];
    }

    public long byType(int type) {
        return byType[type];
    }

    public long pieces(int color, int type) {
        return byType[type] & byColor[color];
    }

    public int pieceAt(int square) {
        return squares[square];
    }

    public boolean isEmpty(int square) {
        return squares[square] == Pieces.NO_PIECE;
    }

    public int kingSquare(int color) {
        return Bitboards.lsb(byType[Pieces.KING] & byColor[color]);
    }

    /** Whether any {@code attacker}-coloured piece attacks {@code square}. */
    public boolean isAttacked(int square, int attacker) {
        long theirs = byColor[attacker];

        // A pawn of ours attacks `square` exactly when it stands on a square that a pawn of the
        // opposite colour, placed on `square`, would attack. Pawn attacks reverse that way.
        if ((Attacks.pawn(Pieces.flip(attacker), square) & byType[Pieces.PAWN] & theirs) != 0) {
            return true;
        }
        if ((Attacks.knight(square) & byType[Pieces.KNIGHT] & theirs) != 0) {
            return true;
        }
        if ((Attacks.king(square) & byType[Pieces.KING] & theirs) != 0) {
            return true;
        }
        long diagonal = (byType[Pieces.BISHOP] | byType[Pieces.QUEEN]) & theirs;
        if ((Attacks.bishop(square, occupied) & diagonal) != 0) {
            return true;
        }
        long straight = (byType[Pieces.ROOK] | byType[Pieces.QUEEN]) & theirs;
        return (Attacks.rook(square, occupied) & straight) != 0;
    }

    public boolean isKingAttacked(int color) {
        return isAttacked(kingSquare(color), Pieces.flip(color));
    }

    public boolean inCheck() {
        return isKingAttacked(sideToMove);
    }

    /**
     * Whether this exact position has occurred earlier on the undo stack.
     *
     * <p>Only positions with the same side to move can repeat, so the scan steps back two plies at
     * a time. It stops at the last pawn move or capture: the halfmove clock counts exactly those,
     * and anything before one of them is unreachable, so scanning further would be wasted work at
     * best and a false positive across a cleared history at worst.
     *
     * <p>A single earlier occurrence is enough to report here rather than the three the rules
     * require. That is the conventional choice inside a search — a position the side to move can
     * already force once can be forced again, so treating the second occurrence as a draw finds the
     * same outcome a couple of plies earlier.
     */
    public boolean isRepetition() {
        int earliest = Math.max(0, ply - halfmoveClock);
        for (int i = ply - 2; i >= earliest; i -= 2) {
            if (undoZobrist[i] == zobrist) {
                return true;
            }
        }
        return false;
    }

    /** True once fifty full moves have passed with no capture and no pawn move. */
    public boolean isFiftyMoveDraw() {
        return halfmoveClock >= 100;
    }

    public void makeMove(int move) {
        int from = Move.from(move);
        int to = Move.to(move);
        int flags = Move.flags(move);
        int piece = squares[from];
        int us = sideToMove;

        undoCaptured[ply] = Pieces.NO_PIECE;
        undoCastling[ply] = castlingRights;
        undoEp[ply] = epSquare;
        undoHalfmove[ply] = halfmoveClock;
        undoZobrist[ply] = zobrist;

        if (epSquare != Squares.NONE) {
            zobrist ^= Zobrist.EP_FILE[Squares.file(epSquare)];
        }

        if (flags == Move.EP_CAPTURE) {
            // The captured pawn is not on the destination square. This is the single most
            // reliable source of en passant bugs.
            int capturedSquare = us == Pieces.WHITE ? to - 8 : to + 8;
            undoCaptured[ply] = squares[capturedSquare];
            removePiece(capturedSquare);
        } else if (Move.isCapture(move)) {
            undoCaptured[ply] = squares[to];
            removePiece(to);
        }

        movePiece(from, to);

        if (Move.isPromotion(move)) {
            removePiece(to);
            putPiece(Pieces.of(us, Move.promotionType(move)), to);
        }

        if (flags == Move.CASTLE_KING) {
            movePiece(to + 1, to - 1);
        } else if (flags == Move.CASTLE_QUEEN) {
            movePiece(to - 2, to + 1);
        }

        int rights = castlingRights & CASTLING_MASK[from] & CASTLING_MASK[to];
        if (rights != castlingRights) {
            zobrist ^= Zobrist.CASTLING[castlingRights] ^ Zobrist.CASTLING[rights];
            castlingRights = rights;
        }

        epSquare = Squares.NONE;
        if (flags == Move.DOUBLE_PUSH) {
            epSquare = us == Pieces.WHITE ? from + 8 : from - 8;
            zobrist ^= Zobrist.EP_FILE[Squares.file(epSquare)];
        }

        if (Pieces.typeOf(piece) == Pieces.PAWN || Move.isCapture(move)) {
            halfmoveClock = 0;
        } else {
            halfmoveClock++;
        }
        if (us == Pieces.BLACK) {
            fullmoveNumber++;
        }

        sideToMove = Pieces.flip(us);
        zobrist ^= Zobrist.SIDE;
        ply++;
    }

    public void unmakeMove(int move) {
        ply--;
        int from = Move.from(move);
        int to = Move.to(move);
        int flags = Move.flags(move);
        sideToMove = Pieces.flip(sideToMove);
        int us = sideToMove;

        if (flags == Move.CASTLE_KING) {
            movePiece(to - 1, to + 1);
        } else if (flags == Move.CASTLE_QUEEN) {
            movePiece(to + 1, to - 2);
        }

        if (Move.isPromotion(move)) {
            removePiece(to);
            putPiece(Pieces.of(us, Pieces.PAWN), to);
        }

        movePiece(to, from);

        if (flags == Move.EP_CAPTURE) {
            putPiece(undoCaptured[ply], us == Pieces.WHITE ? to - 8 : to + 8);
        } else if (Move.isCapture(move)) {
            putPiece(undoCaptured[ply], to);
        }

        castlingRights = undoCastling[ply];
        epSquare = undoEp[ply];
        halfmoveClock = undoHalfmove[ply];
        // Restored wholesale rather than unwound: the put/remove calls above have been scribbling
        // on it, and one assignment is both cheaper and impossible to get subtly wrong.
        zobrist = undoZobrist[ply];
        if (us == Pieces.BLACK) {
            fullmoveNumber--;
        }
    }

    /**
     * Passes the turn without moving anything.
     *
     * <p>Not a chess move — it is the search asking "if I did nothing at all, would this position
     * still be good enough to stop looking?". Almost any real move beats doing nothing, so a position
     * that survives a free move for the opponent is usually beyond saving for them.
     *
     * <p>The en passant square is cleared, because the right to capture it belonged to the move that
     * is not being made.
     */
    public void makeNullMove() {
        undoCaptured[ply] = Pieces.NO_PIECE;
        undoCastling[ply] = castlingRights;
        undoEp[ply] = epSquare;
        undoHalfmove[ply] = halfmoveClock;
        undoZobrist[ply] = zobrist;

        if (epSquare != Squares.NONE) {
            zobrist ^= Zobrist.EP_FILE[Squares.file(epSquare)];
            epSquare = Squares.NONE;
        }

        halfmoveClock++;
        if (sideToMove == Pieces.BLACK) {
            fullmoveNumber++;
        }
        sideToMove = Pieces.flip(sideToMove);
        zobrist ^= Zobrist.SIDE;
        ply++;
    }

    public void unmakeNullMove() {
        ply--;
        sideToMove = Pieces.flip(sideToMove);
        castlingRights = undoCastling[ply];
        epSquare = undoEp[ply];
        halfmoveClock = undoHalfmove[ply];
        zobrist = undoZobrist[ply];
        if (sideToMove == Pieces.BLACK) {
            fullmoveNumber--;
        }
    }

    /**
     * Whether {@code color} has anything but pawns and a king.
     *
     * <p>The search asks before passing the turn. In an endgame of kings and pawns, being forced to
     * move is often a disadvantage — zugzwang — so "doing nothing is bad for me" stops being a safe
     * assumption, and a null move can prune a line that was actually lost.
     */
    public boolean hasNonPawnMaterial(int color) {
        return (byColor[color] & ~(byType[Pieces.PAWN] | byType[Pieces.KING])) != 0;
    }

    /**
     * The hash computed from scratch. Used to check the incremental updates in
     * {@link #makeMove(int)} against a definition that cannot drift, and to seed a position
     * parsed from FEN.
     */
    public long recomputeZobrist() {
        long hash = 0;
        for (int square = 0; square < Squares.COUNT; square++) {
            int piece = squares[square];
            if (piece != Pieces.NO_PIECE) {
                hash ^= Zobrist.PIECE[piece][square];
            }
        }
        hash ^= Zobrist.CASTLING[castlingRights];
        if (epSquare != Squares.NONE) {
            hash ^= Zobrist.EP_FILE[Squares.file(epSquare)];
        }
        if (sideToMove == Pieces.BLACK) {
            hash ^= Zobrist.SIDE;
        }
        return hash;
    }

    /**
     * Every field of the position in one string, so a test can assert that make followed by
     * unmake restored all of it — bitboards, square array, hash and counters alike — rather than
     * the handful of fields someone remembered to check.
     */
    public String stateSignature() {
        StringBuilder out = new StringBuilder(320);
        for (long board : byType) {
            out.append(Long.toHexString(board)).append('/');
        }
        for (long board : byColor) {
            out.append(Long.toHexString(board)).append('/');
        }
        out.append(Long.toHexString(occupied)).append('/');
        for (int piece : squares) {
            out.append(piece).append(',');
        }
        out.append(sideToMove).append('/')
                .append(castlingRights).append('/')
                .append(epSquare).append('/')
                .append(halfmoveClock).append('/')
                .append(fullmoveNumber).append('/')
                .append(Long.toHexString(zobrist));
        return out.toString();
    }

    private void putPiece(int piece, int square) {
        long bit = Bitboards.bit(square);
        byType[Pieces.typeOf(piece)] |= bit;
        byColor[Pieces.colorOf(piece)] |= bit;
        occupied |= bit;
        squares[square] = piece;
        zobrist ^= Zobrist.PIECE[piece][square];
    }

    private void removePiece(int square) {
        int piece = squares[square];
        long bit = Bitboards.bit(square);
        byType[Pieces.typeOf(piece)] &= ~bit;
        byColor[Pieces.colorOf(piece)] &= ~bit;
        occupied &= ~bit;
        squares[square] = Pieces.NO_PIECE;
        zobrist ^= Zobrist.PIECE[piece][square];
    }

    /** Requires {@code to} to be empty; captures are removed by the caller first. */
    private void movePiece(int from, int to) {
        int piece = squares[from];
        long bits = Bitboards.bit(from) | Bitboards.bit(to);
        byType[Pieces.typeOf(piece)] ^= bits;
        byColor[Pieces.colorOf(piece)] ^= bits;
        occupied ^= bits;
        squares[from] = Pieces.NO_PIECE;
        squares[to] = piece;
        zobrist ^= Zobrist.PIECE[piece][from] ^ Zobrist.PIECE[piece][to];
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(200);
        for (int rank = 7; rank >= 0; rank--) {
            out.append(rank + 1).append("  ");
            for (int file = 0; file < 8; file++) {
                int piece = squares[Squares.of(file, rank)];
                out.append(piece == Pieces.NO_PIECE ? '.' : Pieces.toChar(piece)).append(' ');
            }
            out.append('\n');
        }
        out.append("\n   a b c d e f g h\n\n").append(toFen()).append('\n');
        return out.toString();
    }
}
