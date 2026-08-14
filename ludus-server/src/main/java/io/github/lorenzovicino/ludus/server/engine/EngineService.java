package io.github.lorenzovicino.ludus.server.engine;

import io.github.lorenzovicino.ludus.core.Board;
import io.github.lorenzovicino.ludus.core.Move;
import io.github.lorenzovicino.ludus.search.SearchInfo;
import io.github.lorenzovicino.ludus.search.SearchLimits;
import io.github.lorenzovicino.ludus.search.SearchResult;
import io.github.lorenzovicino.ludus.server.domain.Difficulty;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/**
 * The only thing in this module that asks the engine to think.
 *
 * <p>A facade over {@link EnginePool} so nothing above it holds a {@code Search}, knows that engines are
 * pooled, or can accidentally keep one. Controllers ask for a move and get a record back.
 *
 * <p>It is also where the measurements go. The engine already produces the interesting numbers —
 * nodes, depth reached, time spent — and they are worth having per request rather than only in a
 * benchmark: a node rate that halves under load is a fact about the service, not about the search.
 */
@Service
public class EngineService {

    private final EnginePool pool;
    private final Timer searchTimer;
    private final MeterRegistry registry;

    public EngineService(EnginePool pool, MeterRegistry registry) {
        this.pool = pool;
        this.registry = registry;
        this.searchTimer = Timer.builder("ludus.search.duration")
                .description("Wall time of one engine search, from borrowing to answering")
                .publishPercentileHistogram()
                .register(registry);

        // A gauge rather than a counter: the interesting question about a pool is never how many times
        // it was used, it is whether anything was left when the last caller arrived.
        registry.gauge("ludus.engine.available", pool, EnginePool::available);
        registry.gauge("ludus.engine.size", pool, EnginePool::size);
    }

    /** Searches the position and returns the move the engine would play. */
    public EngineMove chooseMove(Board board, Difficulty difficulty) throws InterruptedException {
        return search(board, limitsFor(difficulty), info -> { });
    }

    /**
     * Searches the position, reporting every completed iteration as it happens.
     *
     * <p>The callback runs on the searching thread, so it must not block: it is a request thread's
     * response stream on the other end, and stalling here stalls the search itself.
     */
    public EngineMove analyse(Board board, int depth, Consumer<Iteration> onIteration)
            throws InterruptedException {
        SearchLimits limits = new SearchLimits(
                Math.min(depth, Difficulty.MAXIMUM.depth()),
                Difficulty.MAXIMUM.limit().toNanos(),
                Difficulty.MAXIMUM.limit().toNanos());
        return search(board, limits, onIteration);
    }

    private EngineMove search(Board board, SearchLimits limits, Consumer<Iteration> onIteration)
            throws InterruptedException {
        Timer.Sample sample = Timer.start(registry);
        try {
            return pool.withEngine(engine -> {
                engine.setListener(info -> onIteration.accept(Iteration.of(info)));
                SearchResult result = engine.search(board, limits);
                return new EngineMove(
                        result.hasMove() ? Move.toUci(result.bestMove()) : null,
                        result.score(),
                        result.depth(),
                        result.nodes());
            });
        } finally {
            sample.stop(searchTimer);
        }
    }

    private static SearchLimits limitsFor(Difficulty difficulty) {
        long nanos = difficulty.limit().toNanos();
        // Both caps, and whichever binds first wins. See Difficulty for why neither alone is enough.
        return new SearchLimits(difficulty.depth(), nanos, nanos);
    }

    public String evaluationName() {
        return pool.evaluationName();
    }

    /** What the engine decided. */
    public record EngineMove(String move, int score, int depth, long nodes) {

        public boolean hasMove() {
            return move != null;
        }
    }

    /** One completed iteration of the search, ready to be sent to a browser. */
    public record Iteration(int depth, int score, long nodes, long elapsedMillis,
                            long nodesPerSecond, List<String> principalVariation) {

        static Iteration of(SearchInfo info) {
            List<String> line = new ArrayList<>(info.pv().length);
            for (int move : info.pv()) {
                line.add(Move.toUci(move));
            }
            return new Iteration(info.depth(), info.score(), info.nodes(), info.elapsedMillis(),
                    info.nodesPerSecond(), line);
        }
    }
}
