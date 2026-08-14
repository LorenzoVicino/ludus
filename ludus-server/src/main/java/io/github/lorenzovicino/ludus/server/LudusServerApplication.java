package io.github.lorenzovicino.ludus.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * An HTTP service that lets a browser play the engine.
 *
 * <p>This module depends on the engine exactly the way {@code ludus-tools} does, and the dependency
 * only points one way: nothing under {@code ludus-search} or {@code ludus-core} knows that a web
 * server exists, and the module graph makes referring to this from there a compile error. The engine
 * remains a program that reads a position and returns a move.
 *
 * <p>Spring's dependency tree stops at this module too — its BOM is imported in this POM rather than
 * the parent — so the jar a chess GUI launches still has no third-party dependencies at all.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LudusServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LudusServerApplication.class, args);
    }
}
