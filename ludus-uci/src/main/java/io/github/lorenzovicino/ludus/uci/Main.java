package io.github.lorenzovicino.ludus.uci;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Entry point. Reads UCI on standard input, answers on standard output. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        BufferedReader input =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = System.out;

        // Flushed after every line. A GUI reads us through a pipe and will sit waiting forever for
        // a reply stuck in a buffer.
        new UciEngine(input, line -> {
            out.println(line);
            out.flush();
        }).run();
    }
}
