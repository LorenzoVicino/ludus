package io.github.lorenzovicino.ludus.server.service;

import io.github.lorenzovicino.ludus.server.persistence.GameRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes games nobody has touched for a while.
 *
 * <h2>Why this has to exist before the service faces the internet</h2>
 *
 * <p>Creating a game costs one row and no ceremony — no account, no confirmation — which is what makes the
 * thing pleasant to try and also means the table grows for as long as the service is up. Anything reachable
 * from the public internet gets crawled, and a crawler that follows a form is indistinguishable from a
 * visitor who starts a game and leaves.
 *
 * <p>So games have a life. The default is generous by the standards of an abandoned game and short by the
 * standards of a database: long enough that coming back to a link tomorrow works, short enough that a month
 * of crawlers is not stored forever.
 *
 * <p><strong>Nothing is anonymised or archived first, and that is deliberate.</strong> A game holds a
 * starting position and a list of moves. There is no account, no address, nothing about who played it —
 * so there is nothing to keep and no reason to keep it.
 */
@Component
public class GameReaper {

    private static final Logger log = LoggerFactory.getLogger(GameReaper.class);

    private final GameRepository games;
    private final Clock clock;
    private final Duration keepFor;

    public GameReaper(GameRepository games, Clock clock,
                      @Value("${ludus.games.keep-for:P14D}") Duration keepFor) {
        this.games = games;
        this.clock = clock;
        this.keepFor = keepFor;
    }

    /**
     * Hourly rather than nightly, so a burst is cleared within the hour instead of accumulating until
     * whatever time somebody once chose for a cron.
     */
    @Scheduled(fixedDelay = 1, timeUnit = java.util.concurrent.TimeUnit.HOURS, initialDelay = 5)
    @Transactional
    public void sweep() {
        Instant before = clock.instant().minus(keepFor);
        long removed = games.deleteByUpdatedAtBefore(before);
        if (removed > 0) {
            log.info("removed {} game(s) untouched since {}", removed, before);
        }
    }
}
