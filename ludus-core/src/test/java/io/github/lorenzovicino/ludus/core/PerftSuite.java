package io.github.lorenzovicino.ludus.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the perft positions from {@code perft-suite.txt}.
 *
 * <p>The suite is data, not code, so adding a position means editing a text file and re-checking
 * one number against the source.
 */
final class PerftSuite {

    private static final String RESOURCE = "/perft-suite.txt";

    /**
     * Cases at or below this many nodes run on every push; the rest are tagged {@code slow}.
     * The cut is by node count rather than depth because depth means different amounts of work in
     * different positions — Kiwipete at depth 4 is heavier than the initial position at depth 5.
     */
    static final long FAST_NODE_LIMIT = 5_000_000L;

    private PerftSuite() {
    }

    record Case(String name, String fen, int depth, long nodes) {
        @Override
        public String toString() {
            return name + " depth " + depth + " (" + nodes + " nodes)";
        }
    }

    static List<Case> all() {
        List<Case> cases = new ArrayList<>();
        try (InputStream in = PerftSuite.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource " + RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] fields = line.split(";");
                    if (fields.length < 3) {
                        throw new IllegalStateException("Malformed suite line: " + line);
                    }
                    for (int i = 2; i < fields.length; i++) {
                        String[] pair = fields[i].split(":");
                        if (pair.length != 2) {
                            throw new IllegalStateException("Malformed depth:nodes pair '" + fields[i] + "'");
                        }
                        cases.add(new Case(fields[0], fields[1],
                                Integer.parseInt(pair[0]), Long.parseLong(pair[1])));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (cases.isEmpty()) {
            throw new IllegalStateException("Perft suite is empty — the resource did not load");
        }
        return cases;
    }

    static List<Case> fast() {
        return all().stream().filter(c -> c.nodes() <= FAST_NODE_LIMIT).toList();
    }
}
