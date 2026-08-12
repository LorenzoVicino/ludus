package io.github.lorenzovicino.ludus.tools;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.eval.Evaluator;
import io.github.lorenzovicino.ludus.eval.HandCraftedEvaluator;
import io.github.lorenzovicino.ludus.nnue.NnueEvaluator;
import io.github.lorenzovicino.ludus.nnue.NnueNetwork;
import io.github.lorenzovicino.ludus.search.Search;
import io.github.lorenzovicino.ludus.search.SearchLimits;
import io.github.lorenzovicino.ludus.search.SearchResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Searches a fixed position set to a fixed depth and reports how fast it went.
 *
 * <pre>
 * java -jar ludus-match.jar bench --depth 8
 * java -jar ludus-match.jar bench --depth 8 --nnue build/ludus.nnue
 * </pre>
 *
 * <p>This is the metric DESIGN.md §1.3 exists for: it separates the two ways a change can hurt.
 * <em>Searching worse</em> shows up as Elo falling with nodes per second unchanged; <em>searching
 * slower</em> shows up here. They are different bugs, and without this measurement they look
 * identical from a match result.
 *
 * <p>Fixed depth rather than fixed time, deliberately. Fixed time would hide exactly what is being
 * measured: a slower engine would simply search less and report the same rate.
 */
public final class BenchMain {

    /** A spread of position types, so a change that only hurts endgames has somewhere to show. */
    private static final List<String> POSITIONS = List.of(
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
            "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1",
            "r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10",
            "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
            "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
            "8/5k2/3p4/1p1Pp2p/pP2Pp1P/P4P1K/8/8 b - - 0 1",
            "4rrk1/pp1n1pp1/2pb1q1p/3p4/3P1B2/2NBP2P/PPQ2PP1/R3R1K1 w - - 0 18",
            "2r3k1/1p3pp1/p2p3p/P1nPp3/1PP1P3/5PP1/2R3KP/2R5 b - - 0 30");

    private BenchMain() {
    }

    public static int run(String[] args) throws Exception {
        int depth = 8;
        Path network = null;
        boolean compare = false;
        Path predictFrom = null;
        int sample = 20_000;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--depth" -> depth = Integer.parseInt(value(args, ++i, "--depth"));
                case "--nnue" -> network = Path.of(value(args, ++i, "--nnue"));
                case "--compare" -> compare = true;
                case "--predict" -> predictFrom = Path.of(value(args, ++i, "--predict"));
                case "--sample" -> sample = Integer.parseInt(value(args, ++i, "--sample"));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        if (predictFrom != null) {
            if (network == null) {
                throw new IllegalArgumentException("--predict needs --nnue");
            }
            return predict(NnueNetwork.load(network), predictFrom, sample);
        }

        if (compare) {
            if (network == null) {
                throw new IllegalArgumentException("--compare needs --nnue");
            }
            return compareEvaluations(NnueNetwork.load(network));
        }

        Evaluator evaluator = network == null
                ? new HandCraftedEvaluator()
                : new NnueEvaluator(NnueNetwork.load(network));
        String label = network == null ? "hand-crafted" : "network " + network.getFileName();

        // One untimed pass first. Measuring a cold JVM measures the interpreter, not the engine.
        warmUp(evaluator, Math.min(depth, 6));

        Search search = new Search(evaluator);
        long nodes = 0;
        long start = System.nanoTime();

        for (String fen : POSITIONS) {
            Board board = Board.fromFen(fen);
            SearchResult result = search.search(board, SearchLimits.depth(depth));
            nodes += result.nodes();
        }

        long millis = (System.nanoTime() - start) / 1_000_000;
        long nps = millis == 0 ? nodes : nodes * 1000 / millis;

        System.out.printf(Locale.ROOT,
                "%-28s depth %d  %,12d nodes  %6d ms  %,10d nodes/s%n",
                label, depth, nodes, millis, nps);
        return 0;
    }

    /**
     * Prints what each evaluation says about the same positions.
     *
     * <p>A network trained on scores its own engine produced can only ever approximate that engine,
     * and an approximation that costs seven times as much to compute is strictly worse. This is how
     * to tell whether that is what happened: if the two columns track each other closely, the network
     * learned to imitate rather than to judge, and the fix is a better teacher rather than more data
     * from the same one.
     */
    /** Phase boundaries by piece count, kings included. */
    private static final int[] PHASE_LIMITS = {6, 10, 16, 24, 32};
    private static final String[] PHASE_NAMES =
            {"bare endgame", "endgame", "late middlegame", "middlegame", "opening"};

    /** Labels this far from level are mate scores or blunders, and would swamp a mean. */
    private static final int LABEL_LIMIT = 2_000;

    /**
     * Bands of label magnitude, because a mean in centipawns is not a fair test on its own.
     *
     * <p>Training minimises squared error on {@code sigmoid(cp/400)}. At 2000 centipawns that sigmoid
     * reads 0.993, so the target is saturated and the network is barely taught to separate +500 from
     * +900 — correctly, because no engine needs to: both are winning, and the move played is the same.
     * A mean in centipawns punishes exactly that irrelevant distinction, which would make this metric
     * misleading in the same way comparing against the hand-crafted evaluation was.
     *
     * <p>Split by band, the two cases separate. Being worse near level is a real defect, because that
     * is the band where the sign of a difference decides which move gets played. Being worse only in
     * the far bands is an artefact of the loss and costs little.
     */
    private static final int[] LABEL_BANDS = {50, 150, 400, 1_000, LABEL_LIMIT};

    /**
     * How well each evaluation predicts the labels, by phase.
     *
     * <pre>
     * java -jar ludus-match.jar bench --predict build/selfplay-d10.txt --nnue build/ludus.nnue
     * </pre>
     *
     * <p><strong>This measures something different from {@code --compare}, and the difference is the
     * point.</strong> {@code --compare} reports how far the network sits from the hand-crafted
     * evaluation, which was the right question while the network was a lossy copy of it: agreement in
     * the middlegame meant no upside, and disagreement in endgames meant active error.
     *
     * <p>It is the wrong question now. The labels come from ten-ply searches, and a network trained on
     * those <em>should</em> disagree with the raw hand-crafted evaluation — that disagreement is the
     * knowledge it is supposed to have absorbed. Rewarding agreement would reward the defect.
     *
     * <p>So this asks instead: given a position and what a deep search concluded about it, how close does
     * each evaluation come?
     *
     * <h2>What this cannot be used for, having been used for it</h2>
     *
     * <p>An earlier version of this comment claimed the hand-crafted evaluation was "a fair baseline,
     * <em>because</em> the labels were produced by searching with it". That is exactly backwards. The
     * label is a search score anchored to the hand-crafted evaluation, so near level — where the search
     * correction is small — the hand-crafted number predicts <strong>its own contribution to the
     * target</strong>. It is not a competitor on this metric, it is part of it.
     *
     * <p>The error was not harmless: it produced the confident conclusion that a network "has nothing to
     * offer for its cost", which the numbers here cannot establish. Measured on identical data, a
     * trained network read 0.045 against the hand-crafted evaluation's 0.019 <em>on the network's own
     * training set</em> — a gap that says more about which of the two the target was built from than
     * about which plays better.
     *
     * <p>What the numbers are good for: <strong>comparing networks with each other</strong>, where the
     * bias is identical on both sides, and seeing where in the board's life a network is weakest. The
     * hand-crafted column stays as a reference point, and is labelled as one.
     *
     * <p>Whether a network is worth its cost is a question only a match answers. There is no cheap
     * substitute, which is inconvenient and remains true.
     */
    private static int predict(NnueNetwork network, Path dataset, int sample) throws Exception {
        long lines;
        try (Stream<String> counting = Files.lines(dataset)) {
            lines = counting.count();
        }
        int stride = (int) Math.max(1, lines / Math.max(1, sample));

        HandCraftedEvaluator handCrafted = new HandCraftedEvaluator();
        NnueEvaluator networkEvaluator = new NnueEvaluator(network);

        int buckets = PHASE_LIMITS.length;
        long[] handError = new long[buckets];
        long[] networkError = new long[buckets];
        long[] handWorst = new long[buckets];
        long[] networkWorst = new long[buckets];
        int[] counts = new int[buckets];
        int skipped = 0;

        int bands = LABEL_BANDS.length;
        long[] handByBand = new long[bands];
        long[] networkByBand = new long[bands];
        int[] countsByBand = new int[bands];
        // Error in win-probability terms as well, which is the space the loss actually minimises.
        double[] handProbability = new double[bands];
        double[] networkProbability = new double[bands];

        try (Stream<String> stream = Files.lines(dataset)) {
            Iterator<String> iterator = stream.iterator();
            long index = -1;
            while (iterator.hasNext()) {
                String line = iterator.next();
                index++;
                if (index % stride != 0 || line.isBlank()) {
                    continue;
                }

                int firstBar = line.indexOf('|');
                int secondBar = line.indexOf('|', firstBar + 1);
                if (firstBar < 0 || secondBar < 0) {
                    continue;
                }
                int label = Integer.parseInt(line.substring(firstBar + 1, secondBar));
                if (Math.abs(label) > LABEL_LIMIT) {
                    skipped++;
                    continue;
                }

                Board board = Board.fromFen(line.substring(0, firstBar));
                networkEvaluator.reset(board);

                int hand = handCrafted.evaluate(board);
                int learned = networkEvaluator.evaluate(board);
                long handOff = Math.abs(hand - label);
                long networkOff = Math.abs(learned - label);

                int bucket = bucketFor(board);
                counts[bucket]++;
                handError[bucket] += handOff;
                networkError[bucket] += networkOff;
                handWorst[bucket] = Math.max(handWorst[bucket], handOff);
                networkWorst[bucket] = Math.max(networkWorst[bucket], networkOff);

                int band = bandFor(Math.abs(label));
                countsByBand[band]++;
                handByBand[band] += handOff;
                networkByBand[band] += networkOff;
                double target = winProbability(label);
                handProbability[band] += Math.abs(winProbability(hand) - target);
                networkProbability[band] += Math.abs(winProbability(learned) - target);
            }
        }

        System.out.printf("%,d positions in the file, every %,dth sampled, %,d beyond +/-%d skipped%n%n",
                lines, stride, skipped, LABEL_LIMIT);
        System.out.printf(Locale.ROOT, "%-17s %9s %9s %9s %9s %9s%n",
                "phase", "count", "hand", "network", "worst h", "worst n");

        long totalHand = 0;
        long totalNetwork = 0;
        int total = 0;
        for (int b = 0; b < buckets; b++) {
            if (counts[b] == 0) {
                continue;
            }
            System.out.printf(Locale.ROOT, "%-17s %9d %9d %9d %9d %9d%n",
                    PHASE_NAMES[b], counts[b], handError[b] / counts[b], networkError[b] / counts[b],
                    handWorst[b], networkWorst[b]);
            totalHand += handError[b];
            totalNetwork += networkError[b];
            total += counts[b];
        }

        if (total == 0) {
            System.out.println("no usable positions");
            return 2;
        }

        long hand = totalHand / total;
        long learned = totalNetwork / total;
        System.out.printf(Locale.ROOT, "%n%-17s %9d %9d %9d%n", "all", total, hand, learned);

        // The band table is the one to read. See LABEL_BANDS: a mean in centipawns alone would punish
        // the network for not separating two winning positions, which no engine needs to do.
        System.out.printf(Locale.ROOT, "%n%-17s %9s %9s %9s %9s %9s%n",
                "|label|", "count", "ref cp", "net cp", "ref win%", "net win%");
        for (int b = 0; b < bands; b++) {
            if (countsByBand[b] == 0) {
                continue;
            }
            String label = b == 0 ? "0-" + LABEL_BANDS[0]
                    : LABEL_BANDS[b - 1] + "-" + LABEL_BANDS[b];
            System.out.printf(Locale.ROOT, "%-17s %9d %9d %9d %9.3f %9.3f%n",
                    label, countsByBand[b],
                    handByBand[b] / countsByBand[b], networkByBand[b] / countsByBand[b],
                    handProbability[b] / countsByBand[b], networkProbability[b] / countsByBand[b]);
        }

        System.out.printf("%nmean error against a ten-ply label: reference %d cp, network %d cp%n",
                hand, learned);
        // Deliberately no verdict. The hand-crafted column is not a competitor on this metric: the
        // labels are search scores anchored to it, so near level it is predicting its own contribution
        // to the target. An earlier version of this printed "the network has nothing to offer for its
        // cost" on exactly that comparison, which the numbers cannot support.
        System.out.println("The reference column is the hand-crafted evaluation, which the labels were");
        System.out.println("produced by searching with. It is part of the target, not a rival on it, so");
        System.out.println("do not read these columns as a contest. Compare networks with each other");
        System.out.println("here; compare a network against the hand-crafted evaluation with a match.");
        System.out.println("inference path: " + NnueEvaluator.inferencePath());
        // Not an exit code that gates anything: it reports, and the SPRT decides.
        return 0;
    }

    static int bandFor(int magnitude) {
        for (int b = 0; b < LABEL_BANDS.length; b++) {
            if (magnitude <= LABEL_BANDS[b]) {
                return b;
            }
        }
        return LABEL_BANDS.length - 1;
    }

    /**
     * The same transform training uses, so the two numbers describe the same space.
     *
     * <p>The 400 is not free to differ from {@code SCALE} in {@code features.py}. If the two drift
     * apart, this reports win-probability errors on a different scale from the one the loss minimised,
     * and the comparison quietly stops meaning what it says. {@code PredictionMetricTest} pins it.
     */
    static double winProbability(int centipawns) {
        return 1.0 / (1.0 + Math.exp(-centipawns / 400.0));
    }

    private static int bucketFor(Board board) {
        String placement = board.toFen();
        int pieces = 0;
        for (int i = 0; i < placement.length(); i++) {
            char c = placement.charAt(i);
            if (c == ' ') {
                break;
            }
            if (Character.isLetter(c)) {
                pieces++;
            }
        }
        for (int b = 0; b < PHASE_LIMITS.length; b++) {
            if (pieces <= PHASE_LIMITS[b]) {
                return b;
            }
        }
        return PHASE_LIMITS.length - 1;
    }

    private static int compareEvaluations(NnueNetwork network) {
        HandCraftedEvaluator handCrafted = new HandCraftedEvaluator();
        NnueEvaluator networkEvaluator = new NnueEvaluator(network);

        System.out.printf("%-46s %10s %10s %8s%n", "position", "hand", "network", "diff");
        long totalAbsoluteDifference = 0;
        int count = 0;

        for (String fen : POSITIONS) {
            Board board = Board.fromFen(fen);
            networkEvaluator.reset(board);

            int hand = handCrafted.evaluate(board);
            int learned = networkEvaluator.evaluate(board);
            totalAbsoluteDifference += Math.abs(learned - hand);
            count++;

            System.out.printf("%-46s %10d %10d %+8d%n",
                    fen.substring(0, Math.min(44, fen.length())), hand, learned, learned - hand);
        }

        System.out.printf("%nmean absolute difference: %d centipawns over %d positions%n",
                totalAbsoluteDifference / count, count);
        System.out.println("inference path: " + NnueEvaluator.inferencePath());
        return 0;
    }

    private static void warmUp(Evaluator evaluator, int depth) {
        Search search = new Search(evaluator);
        for (String fen : POSITIONS) {
            search.search(Board.fromFen(fen), SearchLimits.depth(depth));
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
