package io.github.lorenzovicino.ludus.tools;

import io.github.lorenzovicino.ludus.core.Bitboards;
import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.MoveGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** The positions a match starts its games from. */
public final class OpeningBook {

    private OpeningBook() {
    }

    /**
     * Builds a book by walking a few random legal moves out of the start position.
     *
     * <p>Generating beats downloading one. A curated book is a file that has to exist on whatever
     * machine runs the match, which makes CI — and every worker in a distributed match — depend on
     * fetching it. This needs nothing but the move generator that is already here, and a fixed seed
     * makes it reproducible, which matters more than usual now that several machines have to agree
     * on the same openings.
     *
     * <p>Positions where anything has been captured are rejected. Random moves hang pieces, and an
     * opening that starts a rook down tests nothing. Requiring the full complement of material is a
     * cruder filter than an evaluation would be, but it needs no evaluation — keeping this dependent
     * on the core alone — and it is decisive rather than approximate.
     *
     * <p>Residual imbalance matters little anyway: every opening is played twice with the colours
     * swapped, so a position favouring one side hands the same advantage to each engine in turn.
     */
    public static List<String> generate(int count, int plies, long seed) {
        List<String> positions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Random random = new Random(seed);
        int[] moves = new int[MoveGenerator.MAX_MOVES];

        int attempts = 0;
        int attemptLimit = Math.max(1000, count * 200);

        while (positions.size() < count && attempts < attemptLimit) {
            attempts++;
            Board board = Board.startPosition();
            boolean playable = true;

            for (int ply = 0; ply < plies; ply++) {
                int legal = MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves));
                if (legal == 0) {
                    playable = false;
                    break;
                }
                board.makeMove(moves[random.nextInt(legal)]);
            }
            if (!playable || Bitboards.count(board.occupied()) != 32) {
                continue;
            }
            // A position with no moves is over before either engine has played one.
            if (MoveGenerator.filterLegal(board, moves, MoveGenerator.generate(board, moves)) == 0) {
                continue;
            }

            String fen = board.toFen();
            if (seen.add(fen)) {
                positions.add(fen);
            }
        }

        if (positions.isEmpty()) {
            throw new IllegalStateException("Could not generate any openings");
        }
        return positions;
    }

    /**
     * Reads an EPD or FEN book, one position per line, shuffled with a fixed seed so a rerun plays
     * the same openings in the same order.
     */
    public static List<String> load(Path path, long seed) throws IOException {
        List<String> positions = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] fields = trimmed.split("\\s+");
            if (fields.length < 4) {
                continue;
            }
            // An EPD line is a FEN's first four fields followed by opcodes; the clocks are optional
            // and anything after them is not ours to interpret.
            String fen = String.join(" ", Arrays.copyOfRange(fields, 0, 4)) + " 0 1";
            try {
                Board.fromFen(fen);
                positions.add(fen);
            } catch (RuntimeException ignored) {
                // Not a position we can parse; skip rather than abandon the book.
            }
        }
        if (positions.isEmpty()) {
            throw new IllegalArgumentException("No usable positions in " + path);
        }
        Collections.shuffle(positions, new Random(seed));
        return positions;
    }
}
