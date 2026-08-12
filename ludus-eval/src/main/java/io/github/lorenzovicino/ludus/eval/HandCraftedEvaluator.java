package io.github.lorenzovicino.ludus.eval;

import io.github.lorenzovicino.ludus.core.Bitboards;
import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Pieces;
import io.github.lorenzovicino.ludus.core.Squares;

/**
 * Evaluation written by hand: material, piece-square tables, pawn structure, bishop pair.
 *
 * <p><strong>This code is scrap, deliberately.</strong> Its job is not to be good — it is to give
 * Act I a competent opponent and to establish the Elo baseline the NNUE will be measured against
 * (DESIGN.md §6.1). Time spent tuning it is time spent making the Act II result look smaller, so
 * the terms here are the ones that pay immediately and nothing else.
 *
 * <p>What is deliberately missing: king safety, mobility, rooks on open files, and any tuning at
 * all. The constants are reasoned rather than fitted.
 *
 * <p>Scores are tapered between a midgame and an endgame reading, interpolated on the material still
 * on the board. Without that, a king that should be hiding in the opening keeps hiding in a king and
 * pawn endgame, where it needs to be marching instead.
 */
public final class HandCraftedEvaluator implements Evaluator {

    /** Small bonus for having the move, which damps the score swing between odd and even depths. */
    private static final int TEMPO = 10;

    private static final int BISHOP_PAIR_MG = 30;
    private static final int BISHOP_PAIR_EG = 50;
    private static final int DOUBLED_PAWN_MG = -10;
    private static final int DOUBLED_PAWN_EG = -20;
    private static final int ISOLATED_PAWN_MG = -15;
    private static final int ISOLATED_PAWN_EG = -20;

    /** Passed pawn bonus by how far the pawn has advanced, from its own second rank. */
    private static final int[] PASSED_PAWN_MG = {0, 5, 10, 20, 35, 60, 100, 0};
    private static final int[] PASSED_PAWN_EG = {0, 10, 20, 40, 70, 110, 160, 0};

    private static final int[] MATERIAL_MG = {100, 320, 330, 500, 950, 0};
    private static final int[] MATERIAL_EG = {120, 320, 340, 550, 960, 0};

    /**
     * Phase weight per piece. A full complement of minors, rooks and queens sums to
     * {@link #MAX_PHASE}; as pieces come off, the score slides towards the endgame reading.
     */
    private static final int[] PHASE_WEIGHT = {0, 1, 1, 2, 4, 0};
    private static final int MAX_PHASE = 24;

    // Tables are written in reading order — a8 first, h1 last — so they can be checked by eye
    // against a board. INDEX() maps a square to that layout.

    private static final int[] PAWN_MG = {
             0,   0,   0,   0,   0,   0,   0,   0,
            50,  50,  50,  50,  50,  50,  50,  50,
            10,  10,  20,  30,  30,  20,  10,  10,
             5,   5,  10,  25,  25,  10,   5,   5,
             0,   0,   0,  20,  20,   0,   0,   0,
             5,  -5, -10,   0,   0, -10,  -5,   5,
             5,  10,  10, -20, -20,  10,  10,   5,
             0,   0,   0,   0,   0,   0,   0,   0,
    };

    /** In the endgame a pawn's value is almost entirely how close it is to promoting. */
    private static final int[] PAWN_EG = {
             0,   0,   0,   0,   0,   0,   0,   0,
            90,  90,  90,  90,  90,  90,  90,  90,
            55,  55,  55,  55,  55,  55,  55,  55,
            30,  30,  30,  30,  30,  30,  30,  30,
            15,  15,  15,  15,  15,  15,  15,  15,
             5,   5,   5,   5,   5,   5,   5,   5,
             0,   0,   0,   0,   0,   0,   0,   0,
             0,   0,   0,   0,   0,   0,   0,   0,
    };

    private static final int[] KNIGHT = {
           -50, -40, -30, -30, -30, -30, -40, -50,
           -40, -20,   0,   0,   0,   0, -20, -40,
           -30,   0,  10,  15,  15,  10,   0, -30,
           -30,   5,  15,  20,  20,  15,   5, -30,
           -30,   0,  15,  20,  20,  15,   0, -30,
           -30,   5,  10,  15,  15,  10,   5, -30,
           -40, -20,   0,   5,   5,   0, -20, -40,
           -50, -40, -30, -30, -30, -30, -40, -50,
    };

    private static final int[] BISHOP = {
           -20, -10, -10, -10, -10, -10, -10, -20,
           -10,   0,   0,   0,   0,   0,   0, -10,
           -10,   0,   5,  10,  10,   5,   0, -10,
           -10,   5,   5,  10,  10,   5,   5, -10,
           -10,   0,  10,  10,  10,  10,   0, -10,
           -10,  10,  10,  10,  10,  10,  10, -10,
           -10,   5,   0,   0,   0,   0,   5, -10,
           -20, -10, -10, -10, -10, -10, -10, -20,
    };

    private static final int[] ROOK = {
             0,   0,   0,   0,   0,   0,   0,   0,
             5,  10,  10,  10,  10,  10,  10,   5,
            -5,   0,   0,   0,   0,   0,   0,  -5,
            -5,   0,   0,   0,   0,   0,   0,  -5,
            -5,   0,   0,   0,   0,   0,   0,  -5,
            -5,   0,   0,   0,   0,   0,   0,  -5,
            -5,   0,   0,   0,   0,   0,   0,  -5,
             0,   0,   0,   5,   5,   0,   0,   0,
    };

    private static final int[] QUEEN = {
           -20, -10, -10,  -5,  -5, -10, -10, -20,
           -10,   0,   0,   0,   0,   0,   0, -10,
           -10,   0,   5,   5,   5,   5,   0, -10,
            -5,   0,   5,   5,   5,   5,   0,  -5,
             0,   0,   5,   5,   5,   5,   0,  -5,
           -10,   5,   5,   5,   5,   5,   0, -10,
           -10,   0,   5,   0,   0,   0,   0, -10,
           -20, -10, -10,  -5,  -5, -10, -10, -20,
    };

    /** Midgame: get behind the pawns and stay there. */
    private static final int[] KING_MG = {
           -30, -40, -40, -50, -50, -40, -40, -30,
           -30, -40, -40, -50, -50, -40, -40, -30,
           -30, -40, -40, -50, -50, -40, -40, -30,
           -30, -40, -40, -50, -50, -40, -40, -30,
           -20, -30, -30, -40, -40, -30, -30, -20,
           -10, -20, -20, -20, -20, -20, -20, -10,
            20,  20,   0,   0,   0,   0,  20,  20,
            20,  30,  10,   0,   0,  10,  30,  20,
    };

    /** Endgame: the king is a fighting piece and belongs in the middle. */
    private static final int[] KING_EG = {
           -50, -40, -30, -20, -20, -30, -40, -50,
           -30, -20, -10,   0,   0, -10, -20, -30,
           -30, -10,  20,  30,  30,  20, -10, -30,
           -30, -10,  30,  40,  40,  30, -10, -30,
           -30, -10,  30,  40,  40,  30, -10, -30,
           -30, -10,  20,  30,  30,  20, -10, -30,
           -30, -30,   0,   0,   0,   0, -30, -30,
           -50, -30, -30, -30, -30, -30, -30, -50,
    };

    private static final long[] FILES = new long[8];
    private static final long[] ADJACENT_FILES = new long[8];
    /** Own and adjacent files strictly ahead of the square, per colour. */
    private static final long[][] PASSED_SPAN = new long[Pieces.COLOR_COUNT][Squares.COUNT];

    static {
        for (int file = 0; file < 8; file++) {
            FILES[file] = Bitboards.FILE_A << file;
        }
        for (int file = 0; file < 8; file++) {
            long adjacent = 0;
            if (file > 0) {
                adjacent |= FILES[file - 1];
            }
            if (file < 7) {
                adjacent |= FILES[file + 1];
            }
            ADJACENT_FILES[file] = adjacent;
        }
        for (int square = 0; square < Squares.COUNT; square++) {
            int file = Squares.file(square);
            int rank = Squares.rank(square);
            long lane = FILES[file] | ADJACENT_FILES[file];

            long aheadWhite = 0;
            for (int r = rank + 1; r < 8; r++) {
                aheadWhite |= Bitboards.RANK_1 << (r * 8);
            }
            long aheadBlack = 0;
            for (int r = rank - 1; r >= 0; r--) {
                aheadBlack |= Bitboards.RANK_1 << (r * 8);
            }
            PASSED_SPAN[Pieces.WHITE][square] = lane & aheadWhite;
            PASSED_SPAN[Pieces.BLACK][square] = lane & aheadBlack;
        }
    }

    /**
     * Whether neither side has the material to deliver mate, making the position a draw whatever the
     * pieces are doing.
     *
     * <p>Counting material without this is wrong in a way that is easy to miss and expensive twice
     * over. {@code 8/8/4k3/8/8/2KB4/8/8} — king and bishop against a bare king, a dead draw — read
     * <strong>+368 centipawns</strong> before this check existed: more than a third of a queen for a
     * position no legal sequence can win.
     *
     * <p>The second cost is the one that mattered here. Training labels come from searches performed
     * with this evaluation, so every drawn ending it overvalued taught the network the same error
     * faithfully. It also showed up in the data as a contradiction between the two halves of a label:
     * across generated endgame positions the search averaged ±400 centipawns while 68% of the games
     * ended drawn.
     *
     * <p>The rule is deliberately the conservative one — no pawns, no rooks, no queens, and at most one
     * minor piece each. With at most two minors on the board split one apiece, there is no forced mate.
     * K+B+B and K+B+N against a bare king are real wins and are left alone; K+N+N is not forced but is
     * not claimed here either, because "cannot be forced" and "is a draw" are different statements and
     * only the second one belongs in an evaluation that returns zero.
     */
    private static boolean isInsufficientMaterial(Board board) {
        if (board.pieces(Pieces.WHITE, Pieces.PAWN) != 0 || board.pieces(Pieces.BLACK, Pieces.PAWN) != 0
                || board.pieces(Pieces.WHITE, Pieces.ROOK) != 0
                || board.pieces(Pieces.BLACK, Pieces.ROOK) != 0
                || board.pieces(Pieces.WHITE, Pieces.QUEEN) != 0
                || board.pieces(Pieces.BLACK, Pieces.QUEEN) != 0) {
            return false;
        }
        return minorCount(board, Pieces.WHITE) <= 1 && minorCount(board, Pieces.BLACK) <= 1;
    }

    private static int minorCount(Board board, int color) {
        return Bitboards.count(board.pieces(color, Pieces.KNIGHT))
                + Bitboards.count(board.pieces(color, Pieces.BISHOP));
    }

    @Override
    public int evaluate(Board board) {
        if (isInsufficientMaterial(board)) {
            return 0;
        }

        int midgame = 0;
        int endgame = 0;
        int phase = 0;

        for (int color = 0; color < Pieces.COLOR_COUNT; color++) {
            int sign = color == Pieces.WHITE ? 1 : -1;
            for (int type = 0; type < Pieces.TYPE_COUNT; type++) {
                long pieces = board.pieces(color, type);
                while (pieces != 0) {
                    int square = Bitboards.lsb(pieces);
                    pieces = Bitboards.popLsb(pieces);
                    int index = tableIndex(color, square);
                    midgame += sign * (MATERIAL_MG[type] + midgameTable(type)[index]);
                    endgame += sign * (MATERIAL_EG[type] + endgameTable(type)[index]);
                    phase += PHASE_WEIGHT[type];
                }
            }

            if (Bitboards.count(board.pieces(color, Pieces.BISHOP)) >= 2) {
                midgame += sign * BISHOP_PAIR_MG;
                endgame += sign * BISHOP_PAIR_EG;
            }

            long ourPawns = board.pieces(color, Pieces.PAWN);
            long theirPawns = board.pieces(Pieces.flip(color), Pieces.PAWN);
            long remaining = ourPawns;
            while (remaining != 0) {
                int square = Bitboards.lsb(remaining);
                remaining = Bitboards.popLsb(remaining);
                int file = Squares.file(square);

                if (Bitboards.count(ourPawns & FILES[file]) > 1) {
                    midgame += sign * DOUBLED_PAWN_MG;
                    endgame += sign * DOUBLED_PAWN_EG;
                }
                if ((ourPawns & ADJACENT_FILES[file]) == 0) {
                    midgame += sign * ISOLATED_PAWN_MG;
                    endgame += sign * ISOLATED_PAWN_EG;
                }
                if ((theirPawns & PASSED_SPAN[color][square]) == 0) {
                    int advanced = color == Pieces.WHITE
                            ? Squares.rank(square) - 1
                            : 6 - Squares.rank(square);
                    if (advanced >= 0 && advanced < PASSED_PAWN_MG.length) {
                        midgame += sign * PASSED_PAWN_MG[advanced];
                        endgame += sign * PASSED_PAWN_EG[advanced];
                    }
                }
            }
        }

        // Promotions can put more material on the board than the opening had, so clamp.
        phase = Math.min(phase, MAX_PHASE);
        int white = (midgame * phase + endgame * (MAX_PHASE - phase)) / MAX_PHASE;

        int sideToMove = board.sideToMove() == Pieces.WHITE ? white : -white;
        return sideToMove + TEMPO;
    }

    /**
     * Maps a square onto the reading-order tables, mirrored for black so both colours read their
     * own back rank as the bottom row.
     */
    private static int tableIndex(int color, int square) {
        return color == Pieces.WHITE ? square ^ 56 : square;
    }

    private static int[] midgameTable(int type) {
        return switch (type) {
            case Pieces.PAWN -> PAWN_MG;
            case Pieces.KNIGHT -> KNIGHT;
            case Pieces.BISHOP -> BISHOP;
            case Pieces.ROOK -> ROOK;
            case Pieces.QUEEN -> QUEEN;
            case Pieces.KING -> KING_MG;
            default -> throw new IllegalArgumentException("Unknown piece type " + type);
        };
    }

    private static int[] endgameTable(int type) {
        return switch (type) {
            case Pieces.PAWN -> PAWN_EG;
            // Minor and major pieces reuse one table across both phases: their best squares barely
            // move, and inventing a second set of untuned numbers would add code without adding
            // information. The pawn and the king are where the phase genuinely changes the answer.
            case Pieces.KNIGHT -> KNIGHT;
            case Pieces.BISHOP -> BISHOP;
            case Pieces.ROOK -> ROOK;
            case Pieces.QUEEN -> QUEEN;
            case Pieces.KING -> KING_EG;
            default -> throw new IllegalArgumentException("Unknown piece type " + type);
        };
    }
}
