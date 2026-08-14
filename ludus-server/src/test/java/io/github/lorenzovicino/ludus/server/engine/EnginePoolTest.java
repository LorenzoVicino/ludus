package io.github.lorenzovicino.ludus.server.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lorenzovicino.ludus.search.Search;
import io.github.lorenzovicino.ludus.search.SearchListener;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pool's job is to make a shared, stateful engine safe to expose to concurrent requests.
 *
 * <p>What is asserted is not that it works when nothing is happening — that is easy — but the three
 * properties that only matter under contention: no two callers hold the same engine, a caller that
 * cannot get one is refused rather than left waiting, and an engine comes back clean.
 */
class EnginePoolTest {

    private static EngineProperties properties(int size, Duration timeout) {
        return new EngineProperties(size, 1, timeout, "");
    }

    @Test
    @DisplayName("engines are handed out and taken back")
    void borrowAndReturn() throws Exception {
        EnginePool pool = new EnginePool(properties(2, Duration.ofSeconds(1)));
        assertEquals(2, pool.available());

        pool.withEngine(engine -> {
            assertEquals(1, pool.available(), "one is out on loan while the work runs");
            return null;
        });
        assertEquals(2, pool.available(), "and back afterwards");
    }

    @Test
    @DisplayName("an engine returns even when the work throws")
    void returnedOnFailure() {
        EnginePool pool = new EnginePool(properties(1, Duration.ofMillis(50)));

        assertThrows(IllegalStateException.class, () -> pool.withEngine(engine -> {
            throw new IllegalStateException("boom");
        }));
        assertEquals(1, pool.available(), "a leaked engine would take the pool down one at a time");
    }

    @Test
    @DisplayName("no two callers ever hold the same engine")
    void enginesAreNotShared() throws Exception {
        int size = 4;
        EnginePool pool = new EnginePool(properties(size, Duration.ofSeconds(5)));
        List<Search> seen = Collections.synchronizedList(new java.util.ArrayList<>());
        CountDownLatch allBorrowed = new CountDownLatch(size);
        CountDownLatch release = new CountDownLatch(1);

        Thread[] threads = new Thread[size];
        for (int i = 0; i < size; i++) {
            threads[i] = new Thread(() -> {
                try {
                    pool.withEngine(engine -> {
                        seen.add(engine);
                        allBorrowed.countDown();
                        try {
                            // Hold it until every thread has one, so all four are out at the same time.
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }

        assertTrue(allBorrowed.await(5, TimeUnit.SECONDS), "all four should get an engine");
        release.countDown();
        for (Thread thread : threads) {
            thread.join(5000);
        }

        assertEquals(size, seen.size());
        for (int i = 0; i < seen.size(); i++) {
            for (int j = i + 1; j < seen.size(); j++) {
                assertNotSame(seen.get(i), seen.get(j),
                        "two requests holding one Search would corrupt each other's tables silently");
            }
        }
    }

    @Test
    @DisplayName("a caller that cannot get an engine is refused, not queued forever")
    void refusesWhenSaturated() throws Exception {
        EnginePool pool = new EnginePool(properties(1, Duration.ofMillis(100)));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger refusals = new AtomicInteger();

        Thread holder = new Thread(() -> {
            try {
                pool.withEngine(engine -> {
                    holding.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertTrue(holding.await(5, TimeUnit.SECONDS));

        long started = System.nanoTime();
        try {
            pool.withEngine(engine -> null);
        } catch (EngineBusyException expected) {
            refusals.incrementAndGet();
        }
        long waitedMillis = (System.nanoTime() - started) / 1_000_000;

        release.countDown();
        holder.join(5000);

        assertEquals(1, refusals.get(), "the only engine was busy, so this should have been refused");
        assertTrue(waitedMillis < 2000,
                "it should give up near its timeout, not hang: waited " + waitedMillis + "ms");
    }

    @Test
    @DisplayName("a returned engine has no listener from the previous caller")
    void listenerIsClearedOnReturn() throws Exception {
        // The bug this prevents: the next request's search pushing progress into a response stream that
        // belongs to a client which has already gone.
        EnginePool pool = new EnginePool(properties(1, Duration.ofSeconds(1)));
        AtomicInteger stale = new AtomicInteger();

        pool.withEngine(engine -> {
            engine.setListener(info -> stale.incrementAndGet());
            return null;
        });

        SearchListener[] captured = new SearchListener[1];
        pool.withEngine(engine -> {
            // No way to read the listener back, so assert the observable consequence instead: a fresh
            // search on the borrowed engine must not reach the previous caller's callback.
            engine.search(io.github.lorenzovicino.ludus.core.Board.startPosition(),
                    io.github.lorenzovicino.ludus.search.SearchLimits.depth(3));
            return captured;
        });

        assertEquals(0, stale.get(),
                "the previous caller's listener must not be called by somebody else's search");
    }
}
