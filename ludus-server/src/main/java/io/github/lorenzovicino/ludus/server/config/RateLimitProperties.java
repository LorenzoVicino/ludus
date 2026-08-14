package io.github.lorenzovicino.ludus.server.config;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How much of the service one caller may use.
 *
 * @param enabled         off by default. On a laptop a limit only gets in the way of the person testing;
 *                        it is the deployment that needs it, and turning it on there is one variable
 * @param readsPerMinute  reads, writes that do not search, and deletes. A database row each
 * @param enginePerMinute anything that makes the engine think. Far smaller, because a search is seconds of
 *                        a core that nobody else can use while it runs — see {@code RateLimitFilter} for
 *                        why the engine pool alone does not cover this
 * @param window          the period both allowances are measured over
 */
@Validated
@ConfigurationProperties(prefix = "ludus.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        @Min(1) int readsPerMinute,
        @Min(1) int enginePerMinute,
        Duration window) {

    public RateLimitProperties {
        if (readsPerMinute <= 0) {
            readsPerMinute = 120;
        }
        if (enginePerMinute <= 0) {
            enginePerMinute = 20;
        }
        if (window == null) {
            window = Duration.ofMinutes(1);
        }
    }
}
