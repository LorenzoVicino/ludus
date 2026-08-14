package io.github.lorenzovicino.ludus.server.engine;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How much of the machine the engine may have.
 *
 * @param poolSize      how many searches may run at once. Defaults to cores minus one, leaving a core
 *                      for the request threads — a saturated CPU makes every response slow, not just
 *                      the ones doing the searching
 * @param hashMegabytes transposition table per engine. Multiplied by {@code poolSize}, so the default
 *                      is far below what a single-user engine would take
 * @param borrowTimeout how long a request waits for a free engine before giving up with 503. Bounded
 *                      on purpose: a queue that grows without limit turns a busy minute into a pile of
 *                      requests whose clients left long ago
 * @param evalFile      a network file, or empty for the hand-written evaluation. The same choice the
 *                      UCI {@code EvalFile} option makes, so the service cannot be configured into a
 *                      state the engine does not already support
 */
@Validated
@ConfigurationProperties(prefix = "ludus.engine")
public record EngineProperties(
        @Min(1) int poolSize,
        @Min(1) int hashMegabytes,
        @NotNull Duration borrowTimeout,
        String evalFile) {

    public EngineProperties {
        if (poolSize <= 0) {
            poolSize = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        }
        if (hashMegabytes <= 0) {
            hashMegabytes = 16;
        }
        if (borrowTimeout == null) {
            borrowTimeout = Duration.ofSeconds(10);
        }
        if (evalFile == null) {
            evalFile = "";
        }
    }

    public boolean usesNetwork() {
        return !evalFile.isBlank();
    }
}
