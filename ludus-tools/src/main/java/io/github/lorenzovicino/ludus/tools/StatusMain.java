package io.github.lorenzovicino.ludus.tools;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Perft;
import io.github.lorenzovicino.ludus.core.PerftSuite;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes the status page, the machine-readable status document, and the two profile cards.
 *
 * <pre>
 * java -jar ludus-tools/target/ludus-status.jar --out docs --commit abc1234
 * </pre>
 *
 * <p>Everything measurable is measured here rather than recorded by hand: the perft suite is
 * recomputed on every run, and the test total is read from the surefire reports the build just wrote.
 * The only hand-kept facts are in {@link StatusHistory}, and they are the ones a build genuinely
 * cannot produce — a two hundred game match takes minutes and needs two versions built side by side.
 *
 * <p>The data is inlined into the page rather than fetched from a sibling JSON file. One request, no
 * loading state, and the page keeps working when opened straight off a filesystem.
 */
public final class StatusMain {

    private static final String TEMPLATE = "/status-page.html";
    private static final String PLACEHOLDER = "__STATUS_JSON__";

    /**
     * Below this many nodes a rate is noise, not a measurement: a depth-1 case finishes in under a
     * millisecond and its timing is dominated by class initialisation and clock granularity. Letting
     * those into the reported range produced a headline minimum of 34 nodes per second.
     */
    private static final long MEANINGFUL_NODES = 1_000_000L;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private StatusMain() {
    }

    private record Measured(int depth, long expected, long actual, long millis, long nps) {
        boolean matched() {
            return expected == actual;
        }
    }

    private record MeasuredPosition(String name, String fen, List<Measured> cases) {
        boolean verified() {
            return cases.stream().allMatch(Measured::matched);
        }
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of("docs");
        Path root = Path.of(".");
        String commit = "local build";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--out" -> out = Path.of(require(args, ++i, "--out"));
                case "--root" -> root = Path.of(require(args, ++i, "--root"));
                case "--commit" -> commit = shorten(require(args, ++i, "--commit"));
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        System.out.println("Recomputing the perft suite...");
        List<MeasuredPosition> perft = measurePerft();

        long totalNodes = 0;
        long totalMillis = 0;
        long fastest = 0;
        long slowest = Long.MAX_VALUE;
        boolean allVerified = true;
        for (MeasuredPosition position : perft) {
            allVerified &= position.verified();
            for (Measured measured : position.cases()) {
                totalNodes += measured.actual();
                totalMillis += measured.millis();
                if (measured.actual() >= MEANINGFUL_NODES && measured.nps() > 0) {
                    fastest = Math.max(fastest, measured.nps());
                    slowest = Math.min(slowest, measured.nps());
                }
            }
        }
        if (slowest == Long.MAX_VALUE) {
            slowest = 0;
        }

        int tests = countTests(root);
        System.out.printf(Locale.ROOT,
                "perft: %,d nodes in %.1f s, %s | tests: %d%n",
                totalNodes, totalMillis / 1000.0,
                allVerified ? "all verified" : "MISMATCH FOUND", tests);

        if (!allVerified) {
            // Publishing a page that says "verified" when it is not would be worse than publishing
            // nothing, so this is a hard failure rather than a warning.
            throw new IllegalStateException(
                    "Perft mismatch: refusing to generate a status page that would misreport it");
        }

        String json = buildJson(perft, tests, totalNodes, totalMillis, slowest, fastest, commit);

        Files.createDirectories(out);
        Files.writeString(out.resolve("index.html"), renderPage(json), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("status.json"), json, StandardCharsets.UTF_8);
        Files.writeString(out.resolve("card-light.svg"),
                SvgCard.render(SvgCard.LIGHT, StatusHistory.LATEST_MATCH, tests, true),
                StandardCharsets.UTF_8);
        Files.writeString(out.resolve("card-dark.svg"),
                SvgCard.render(SvgCard.DARK, StatusHistory.LATEST_MATCH, tests, true),
                StandardCharsets.UTF_8);
        // Tells GitHub Pages to serve the directory as-is instead of running it through Jekyll,
        // which would otherwise ignore any file or folder whose name begins with an underscore.
        Files.writeString(out.resolve(".nojekyll"), "", StandardCharsets.UTF_8);

        System.out.println("Wrote index.html, status.json, card-light.svg, card-dark.svg to " + out);
    }

    private static List<MeasuredPosition> measurePerft() {
        List<MeasuredPosition> positions = new ArrayList<>();
        for (PerftSuite.Position position : PerftSuite.positions()) {
            List<Measured> cases = new ArrayList<>();
            for (PerftSuite.Case testCase : position.cases()) {
                Board board = Board.fromFen(testCase.fen());
                Perft perft = new Perft();

                long start = System.nanoTime();
                long nodes = perft.count(board, testCase.depth());
                long millis = (System.nanoTime() - start) / 1_000_000;

                cases.add(new Measured(testCase.depth(), testCase.nodes(), nodes, millis,
                        millis == 0 ? 0 : nodes * 1000 / millis));
            }
            positions.add(new MeasuredPosition(position.name(), position.fen(), cases));
        }
        return positions;
    }

    /**
     * Sums the test totals the build just wrote into every module's surefire reports.
     *
     * <p>Reading the reports beats keeping the number by hand, which would drift the moment somebody
     * added a test and forgot. The {@code tests} attribute appears once per file, on the
     * {@code testsuite} element, so the first match is the total.
     */
    private static int countTests(Path root) throws IOException {
        Pattern attribute = Pattern.compile("tests=\"(\\d+)\"");
        int total = 0;

        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> reports = paths
                    .filter(path -> !isInsideGitDirectory(path))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName() != null
                            && path.getParent().getFileName().toString().equals("surefire-reports"))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("TEST-") && name.endsWith(".xml");
                    })
                    .toList();

            for (Path report : reports) {
                Matcher matcher = attribute.matcher(Files.readString(report, StandardCharsets.UTF_8));
                if (matcher.find()) {
                    total += Integer.parseInt(matcher.group(1));
                }
            }
        }
        return total;
    }

    /**
     * Whether any path component is literally {@code .git}.
     *
     * <p>Compares components rather than searching the whole path for the text ".git", which is the
     * obvious version and is wrong here in a way that fails silently: every report is named
     * {@code TEST-io.github.lorenzovicino…}, and {@code io.github} contains {@code .git}. The
     * substring form skipped every file and reported zero tests without complaining.
     */
    private static boolean isInsideGitDirectory(Path path) {
        for (Path component : path) {
            if (component.toString().equals(".git")) {
                return true;
            }
        }
        return false;
    }

    private static String buildJson(List<MeasuredPosition> perft, int tests, long totalNodes,
                                    long totalMillis, long slowestNps, long fastestNps,
                                    String commit) {
        StatusHistory.MatchResult match = StatusHistory.LATEST_MATCH;
        Json json = new Json();

        json.beginObject()
                .field("engine", StatusHistory.ENGINE)
                .field("version", StatusHistory.VERSION)
                .field("milestone", StatusHistory.CURRENT_MILESTONE)
                .field("commit", commit)
                .field("generated", STAMP.format(Instant.now()))
                .field("tests", tests)
                .field("perftTotalNodes", totalNodes)
                .field("perftMillis", totalMillis);

        json.name("perftNps").beginObject()
                .field("min", slowestNps)
                .field("max", fastestNps)
                .endObject();

        json.name("elo").beginObject()
                .field("from", match.baseline())
                .field("to", match.candidate())
                .field("value", match.elo(), 1)
                .field("margin", match.margin(), 1)
                .field("wins", match.wins())
                .field("draws", match.draws())
                .field("losses", match.losses())
                .field("percent", match.percent(), 1)
                .field("llr", match.llr(), 2)
                .field("sprtCrossedAt", match.sprtCrossedAt())
                .field("moveTimeMillis", match.moveTimeMillis())
                .endObject();

        json.name("milestones").beginArray();
        for (StatusHistory.Milestone milestone : StatusHistory.MILESTONES) {
            json.beginObject()
                    .field("id", milestone.id())
                    .field("title", milestone.title())
                    .field("criterion", milestone.criterion())
                    .field("state", milestone.done() ? "done" : "todo")
                    .endObject();
        }
        json.endArray();

        json.name("perft").beginArray();
        for (MeasuredPosition position : perft) {
            json.beginObject()
                    .field("name", position.name())
                    .field("fen", position.fen())
                    .name("cases").beginArray();
            for (Measured measured : position.cases()) {
                json.beginObject()
                        .field("depth", measured.depth())
                        .field("nodes", measured.actual())
                        .field("nps", measured.nps())
                        .field("matched", measured.matched())
                        .endObject();
            }
            json.endArray().endObject();
        }
        json.endArray();

        return json.endObject().toString();
    }

    private static String renderPage(String json) throws IOException {
        try (InputStream in = StatusMain.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IllegalStateException("Missing template resource " + TEMPLATE);
            }
            String template = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (!template.contains(PLACEHOLDER)) {
                throw new IllegalStateException(TEMPLATE + " has no " + PLACEHOLDER + " to fill");
            }
            return template.replace(PLACEHOLDER, json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String shorten(String commit) {
        return commit.length() > 7 ? commit.substring(0, 7) : commit;
    }

    private static String require(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
