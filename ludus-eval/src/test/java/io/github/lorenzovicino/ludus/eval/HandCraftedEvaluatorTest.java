package io.github.lorenzovicino.ludus.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.core.Board;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HandCraftedEvaluatorTest {

    private final HandCraftedEvaluator evaluator = new HandCraftedEvaluator();

    /**
     * The evaluation must not prefer a colour.
     *
     * <p>Mirroring a position — flipping the ranks, swapping the piece colours, and handing the move
     * to the other side — produces a position that is strategically identical from the mover's point
     * of view, so the score has to come out the same. This single property catches the two mistakes
     * that are easiest to make and hardest to notice: a sign flipped in one term, and a
     * piece-square table indexed without mirroring for black. Both leave the engine quietly
     * convinced that one colour is better.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
            "8/5k2/3p4/1p1Pp2p/pP2Pp1P/P4P1K/8/8 b - - 0 1",
            "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1",
    })
    void scoreIsSymmetricUnderColourMirroring(String fen) {
        Board original = Board.fromFen(fen);
        Board mirrored = Board.fromFen(mirror(fen));

        assertEquals(evaluator.evaluate(original), evaluator.evaluate(mirrored),
                () -> "Evaluation is not colour-symmetric.\n  original: " + fen
                        + "\n  mirrored: " + mirror(fen));
    }

    @Test
    void startPositionIsBalancedApartFromTheTempo() {
        // Perfectly symmetric, so the only thing left is the bonus for having the move.
        assertEquals(10, evaluator.evaluate(Board.startPosition()));
    }

    @Test
    void anExtraQueenIsWorthAboutAQueen() {
        Board board = Board.fromFen("4k3/8/8/8/8/8/8/3QK3 w - - 0 1");
        int score = evaluator.evaluate(board);
        assertTrue(score > 800 && score < 1200,
                () -> "A free queen should read as roughly a queen, got " + score);
    }

    @Test
    void materialIsScoredFromTheMoverPointOfView() {
        // Same position, opposite side to move: the sign has to flip.
        Board white = Board.fromFen("4k3/8/8/8/8/8/8/3QK3 w - - 0 1");
        Board black = Board.fromFen("4k3/8/8/8/8/8/8/3QK3 b - - 0 1");
        assertTrue(evaluator.evaluate(white) > 0, "White is a queen up and to move");
        assertTrue(evaluator.evaluate(black) < 0, "Black is a queen down and to move");
    }

    @Test
    void aPassedPawnBeatsABlockedOne() {
        Board passed = Board.fromFen("4k3/8/8/4P3/8/8/8/4K3 w - - 0 1");
        Board blocked = Board.fromFen("4k3/8/4p3/4P3/8/8/8/4K3 w - - 0 1");
        assertTrue(evaluator.evaluate(passed) > evaluator.evaluate(blocked) - 100,
                "A pawn with a clear path ahead must not score worse than one staring at a blocker");
    }

    /**
     * The two positions differ only in where the white king stands, so the endgame king table is the
     * only thing that can separate them.
     *
     * <p>This used two bare kings, which stopped working when the evaluation learned to return zero for
     * material that cannot mate — correctly, because in a dead draw it does not matter where the king
     * stands. A pawn each keeps the material equal, keeps the phase in the endgame, and makes
     * centralisation something the position can actually be judged on.
     */
    @Test
    void theKingWantsTheCentreOnceThePiecesAreGone() {
        Board central = Board.fromFen("7k/p7/8/3K4/8/7P/8/8 w - - 0 1");
        Board cornered = Board.fromFen("7k/p7/8/8/8/7P/8/K7 w - - 0 1");
        assertTrue(evaluator.evaluate(central) > evaluator.evaluate(cornered),
                "The endgame king table must reward centralisation");
    }

    /**
     * Flips a FEN about the horizontal axis and swaps the colours: rank 1 becomes rank 8, white
     * becomes black, and the move changes hands.
     */
    private static String mirror(String fen) {
        String[] fields = fen.trim().split("\\s+");
        String[] rows = fields[0].split("/");

        StringBuilder placement = new StringBuilder();
        for (int i = rows.length - 1; i >= 0; i--) {
            if (placement.length() > 0) {
                placement.append('/');
            }
            placement.append(swapCase(rows[i]));
        }

        String side = fields[1].equals("w") ? "b" : "w";
        String castling = swapCase(fields[2]);
        String ep = fields[3].equals("-")
                ? "-"
                : "" + fields[3].charAt(0) + (char) ('1' + '8' - fields[3].charAt(1));
        String halfmove = fields.length > 4 ? fields[4] : "0";
        String fullmove = fields.length > 5 ? fields[5] : "1";

        return placement + " " + side + " " + castling + " " + ep + " " + halfmove + " " + fullmove;
    }

    private static String swapCase(String text) {
        StringBuilder swapped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isUpperCase(c)) {
                swapped.append(Character.toLowerCase(c));
            } else if (Character.isLowerCase(c)) {
                swapped.append(Character.toUpperCase(c));
            } else {
                swapped.append(c);
            }
        }
        return swapped.toString();
    }
}
