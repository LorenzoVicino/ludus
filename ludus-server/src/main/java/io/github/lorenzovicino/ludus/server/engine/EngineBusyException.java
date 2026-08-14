package io.github.lorenzovicino.ludus.server.engine;

import java.time.Duration;

/** Every engine is in use and the wait ran out. Answered with 503 and a {@code Retry-After}. */
public class EngineBusyException extends RuntimeException {

    private final Duration waited;

    public EngineBusyException(Duration waited) {
        super("No engine became available within " + waited.toSeconds() + "s");
        this.waited = waited;
    }

    public Duration waited() {
        return waited;
    }
}
