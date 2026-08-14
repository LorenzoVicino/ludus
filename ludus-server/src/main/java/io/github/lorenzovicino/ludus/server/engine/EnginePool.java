package io.github.lorenzovicino.ludus.server.engine;

import io.github.lorenzovicino.ludus.eval.Evaluator;
import io.github.lorenzovicino.ludus.eval.HandCraftedEvaluator;
import io.github.lorenzovicino.ludus.nnue.NnueEvaluator;
import io.github.lorenzovicino.ludus.nnue.NnueNetwork;
import io.github.lorenzovicino.ludus.search.Search;
import io.github.lorenzovicino.ludus.search.SearchListener;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A fixed set of engines, lent out one request at a time.
 *
 * <h2>Why a pool and not a new engine per request</h2>
 *
 * <p>A {@link Search} is <strong>stateful and single-threaded</strong>. It owns a transposition table, a
 * killer-move table, history counters and preallocated move lists for every ply — megabytes of arrays
 * that exist precisely so the search never allocates while it runs. Two requests sharing one instance
 * would corrupt each other's tables silently and return legal-looking wrong moves. Building a fresh one
 * per request would throw all of that away between moves and allocate the arrays again each time.
 *
 * <p>So: a fixed number of them, borrowed and returned. The number is the real decision. It is
 * <em>cores minus one</em> by default, because search is CPU-bound and a fully saturated machine makes
 * every response slow — including the cheap ones that only read a game. The same reasoning as the
 * broker's prefetch in the match runner: without a bound, the first few callers take the whole machine
 * and everybody else waits behind them.
 *
 * <p>Borrowing is bounded too. A request that cannot get an engine within the timeout is refused with
 * 503 rather than queued indefinitely, because an unbounded queue during a busy minute means computing
 * moves for clients that hung up.
 *
 * <h2>The detail that would have been a bug</h2>
 *
 * <p>{@link Search#setListener} is how the streaming endpoint watches a search. A borrowed engine is
 * returned with its listener <em>cleared</em>. Without that, the next request to borrow this engine
 * would push its progress into the previous request's response stream — which is closed, from a client
 * that has gone. Nothing about that failure would point at the pool.
 */
@Component
public class EnginePool {

    private static final Logger log = LoggerFactory.getLogger(EnginePool.class);

    private final EngineProperties properties;
    private final BlockingQueue<Lease> idle;
    private final int size;
    private final String evaluationName;

    public EnginePool(EngineProperties properties) {
        this.properties = properties;
        this.size = properties.poolSize();

        Function<Void, Evaluator> factory = evaluatorFactory(properties);
        this.evaluationName = properties.usesNetwork()
                ? "network (" + properties.evalFile() + ")"
                : "hand-written";

        List<Lease> engines = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Evaluator evaluator = factory.apply(null);
            Search search = new Search(evaluator);
            search.setHashSize(properties.hashMegabytes());
            engines.add(new Lease(search));
        }
        this.idle = new ArrayBlockingQueue<>(size, false, engines);

        log.info("engine pool ready: {} engines, {} MB hash each, {} evaluation",
                size, properties.hashMegabytes(), evaluationName);
    }

    /**
     * The evaluation every engine in the pool uses, chosen once at startup.
     *
     * <p>Loaded once and shared: {@link NnueNetwork} is immutable weights, while {@link NnueEvaluator}
     * carries the mutable accumulator and must be one per engine. Getting that the wrong way round —
     * sharing the evaluator — is the same silent corruption as sharing a search.
     */
    private static Function<Void, Evaluator> evaluatorFactory(EngineProperties properties) {
        if (!properties.usesNetwork()) {
            return ignored -> new HandCraftedEvaluator();
        }
        NnueNetwork network;
        try {
            network = NnueNetwork.load(Path.of(properties.evalFile()));
        } catch (IOException e) {
            // Failing to start is right. Silently falling back to the other evaluation would mean
            // serving a different engine than the one that was configured, and nobody would notice.
            throw new IllegalStateException(
                    "Cannot load the configured network " + properties.evalFile(), e);
        }
        return ignored -> new NnueEvaluator(network);
    }

    /**
     * Runs {@code work} on a borrowed engine.
     *
     * <p>A callback rather than a {@code borrow()}/{@code release()} pair, so returning the engine is
     * not something a caller can forget on an exception path.
     */
    public <T> T withEngine(EngineWork<T> work) throws InterruptedException {
        Lease lease = idle.poll(properties.borrowTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (lease == null) {
            throw new EngineBusyException(properties.borrowTimeout());
        }
        try {
            lease.search.clearStop();
            return work.run(lease.search);
        } finally {
            lease.search.setListener(SearchListener.NONE);
            idle.offer(lease);
        }
    }

    /** For the health endpoint and the gauge: how many engines are not currently busy. */
    public int available() {
        return idle.size();
    }

    public int size() {
        return size;
    }

    public String evaluationName() {
        return evaluationName;
    }

    @FunctionalInterface
    public interface EngineWork<T> {
        T run(Search search);
    }

    /** A holder rather than the bare {@link Search}, so the queue's element type says what it is. */
    private record Lease(Search search) {
    }
}
