package io.github.lorenzovicino.ludus.tools;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import io.github.lorenzovicino.ludus.core.Pieces;
import io.github.lorenzovicino.ludus.core.Squares;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Random legal endgame positions, built rather than played into.
 *
 * <h2>Why these have to be constructed</h2>
 *
 * <p>Self-play between two copies of the same engine ends in the middlegame or by the fifty-move
 * rule. It almost never reaches a real endgame, so a dataset generated that way is thin exactly where
 * a tapered evaluation behaves least like itself — and the first trained network showed it, agreeing
 * with the hand-crafted evaluation to within tens of centipawns in the middlegame and missing by
 * three hundred in a rook ending.
 *
 * <p>Walking further into games would not fix it: the games are not going there. Positions of the
 * right shape have to be placed on the board directly.
 *
 * <p>Material sets are drawn from endings that actually occur, rather than uniformly random pieces,
 * so the network learns positions it will meet rather than curiosities.
 */
public final class EndgameSeeds {

    /** Piece types per side, as {@code {white…}, {black…}}, kings implied. */
    private static final int[][][] MATERIAL = {
            {{Pieces.ROOK}, {Pieces.ROOK}},
            {{Pieces.ROOK}, {}},
            {{Pieces.QUEEN}, {Pieces.ROOK}},
            {{Pieces.QUEEN}, {}},
            {{Pieces.BISHOP, Pieces.KNIGHT}, {Pieces.ROOK}},
            {{Pieces.BISHOP}, {Pieces.BISHOP}},
            {{Pieces.KNIGHT}, {Pieces.KNIGHT}},
            {{Pieces.ROOK, Pieces.PAWN, Pieces.PAWN}, {Pieces.ROOK, Pieces.PAWN}},
            {{Pieces.PAWN, Pieces.PAWN, Pieces.PAWN}, {Pieces.PAWN, Pieces.PAWN}},
            {{Pieces.PAWN, Pieces.PAWN}, {Pieces.PAWN, Pieces.PAWN, Pieces.PAWN}},
            {{Pieces.PAWN}, {}},
            {{Pieces.BISHOP, Pieces.PAWN, Pieces.PAWN}, {Pieces.KNIGHT, Pieces.PAWN}},
            {{Pieces.QUEEN, Pieces.PAWN}, {Pieces.QUEEN, Pieces.PAWN}},
            {{Pieces.ROOK, Pieces.BISHOP}, {Pieces.ROOK, Pieces.KNIGHT}},
    };

    private EndgameSeeds() {
    }

    public static List<String> generate(int count, long seed) {
        List<String> positions = new ArrayList<>();
        Random random = new Random(seed);
        int[] scratch = new int[MoveGenerator.MAX_MOVES];

        int attempts = 0;
        int attemptLimit = Math.max(2000, count * 400);

        while (positions.size() < count && attempts < attemptLimit) {
            attempts++;
            String fen = tryOne(random);
            if (fen == null) {
                continue;
            }
            Board board;
            try {
                board = Board.fromFen(fen);
            } catch (RuntimeException malformed) {
                continue;
            }
            // The side that just moved cannot still be attacked, and a position with no moves is
            // over before the generator has played one.
            if (board.isKingAttacked(Pieces.flip(board.sideToMove()))) {
                continue;
            }
            if (MoveGenerator.filterLegal(board, scratch, MoveGenerator.generate(board, scratch)) == 0) {
                continue;
            }
            positions.add(fen);
        }

        if (positions.isEmpty()) {
            throw new IllegalStateException("Could not generate any endgames");
        }
        return positions;
    }

    private static String tryOne(Random random) {
        int[] squares = new int[Squares.COUNT];
        java.util.Arrays.fill(squares, Pieces.NO_PIECE);

        int whiteKing = random.nextInt(Squares.COUNT);
        int blackKing = random.nextInt(Squares.COUNT);
        // Kings may not stand next to each other, and the check is cheap enough to do by hand here.
        if (whiteKing == blackKing || areAdjacent(whiteKing, blackKing)) {
            return null;
        }
        squares[whiteKing] = Pieces.of(Pieces.WHITE, Pieces.KING);
        squares[blackKing] = Pieces.of(Pieces.BLACK, Pieces.KING);

        int[][] material = MATERIAL[random.nextInt(MATERIAL.length)];
        for (int color = 0; color < Pieces.COLOR_COUNT; color++) {
            for (int type : material[color]) {
                int square = freeSquare(squares, random, type);
                if (square < 0) {
                    return null;
                }
                squares[square] = Pieces.of(color, type);
            }
        }

        int sideToMove = random.nextInt(2);
        return toFen(squares, sideToMove);
    }

    /** A pawn on the first or eighth rank is not a position, it is a bug. */
    private static int freeSquare(int[] squares, Random random, int type) {
        for (int attempt = 0; attempt < 64; attempt++) {
            int square = random.nextInt(Squares.COUNT);
            if (squares[square] != Pieces.NO_PIECE) {
                continue;
            }
            int rank = Squares.rank(square);
            if (type == Pieces.PAWN && (rank == 0 || rank == 7)) {
                continue;
            }
            return square;
        }
        return -1;
    }

    private static boolean areAdjacent(int a, int b) {
        return Math.abs(Squares.file(a) - Squares.file(b)) <= 1
                && Math.abs(Squares.rank(a) - Squares.rank(b)) <= 1;
    }

    private static String toFen(int[] squares, int sideToMove) {
        StringBuilder fen = new StringBuilder(80);
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int piece = squares[Squares.of(file, rank)];
                if (piece == Pieces.NO_PIECE) {
                    empty++;
                    continue;
                }
                if (empty > 0) {
                    fen.append(empty);
                    empty = 0;
                }
                fen.append(Pieces.toChar(piece));
            }
            if (empty > 0) {
                fen.append(empty);
            }
            if (rank > 0) {
                fen.append('/');
            }
        }
        // No castling rights and no en passant: neither survives into an ending reached this way.
        fen.append(sideToMove == Pieces.WHITE ? " w " : " b ").append("- - 0 1");
        return fen.toString();
    }
}
