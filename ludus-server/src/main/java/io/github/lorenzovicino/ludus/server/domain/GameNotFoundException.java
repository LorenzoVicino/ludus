package io.github.lorenzovicino.ludus.server.domain;

import java.util.UUID;

/** No game with that id. Answered with 404. */
public class GameNotFoundException extends RuntimeException {

    public GameNotFoundException(UUID id) {
        super("No game with id " + id);
    }
}
