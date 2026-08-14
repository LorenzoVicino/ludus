package io.github.lorenzovicino.ludus.server.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lorenzovicino.ludus.server.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A budget per caller, so one visitor cannot take the whole machine.
 *
 * <h2>Why the engine pool is not enough</h2>
 *
 * <p>{@code EnginePool} already stops requests running in parallel beyond the core count, and answers 503
 * once they queue too long. That protects the machine from collapsing; it does not stop one caller from
 * holding every engine, back to back, for as long as they like. A search is <em>seconds</em> of a core, so
 * a single script can make the service unusable for everybody else without ever exceeding the pool.
 *
 * <p>So the expensive endpoints get a much smaller allowance than the cheap ones. Reading a position is a
 * database row and costs nothing; asking the engine to think is CPU that nobody else can then use, and the
 * two should not share a budget.
 *
 * <h2>Two honest limitations</h2>
 *
 * <p><strong>It is per instance, in memory.</strong> Two replicas would each grant the full allowance, so
 * the effective limit doubles. That is correct for the way this is deployed — one instance — and wrong the
 * moment it is not. A shared counter needs Redis, and adding Redis to hold two integers for a service that
 * runs one copy would be worse.
 *
 * <p><strong>It trusts an address.</strong> Behind a proxy the client address is the proxy's, so
 * {@code X-Forwarded-For} is read when it is present — which is only safe because the platform this runs on
 * sets it and strips anything a client sent. Without that guarantee it is a header a caller can forge to
 * get an unlimited budget, and the code says so rather than looking careful.
 *
 * <p>Hand-written rather than Bucket4j: the whole mechanism is a counter and a timestamp per caller, and a
 * dependency to hold that is not obviously simpler. The trade-off would go the other way for anything
 * distributed or with real quota semantics.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String TYPE =
            "https://github.com/LorenzoVicino/ludus/problems/too-many-requests";

    private final RateLimitProperties properties;
    private final ObjectMapper json;

    /** One window per caller per class of request. Swept rather than left to grow without bound. */
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong lastSweep = new AtomicLong(System.nanoTime());

    public RateLimitFilter(RateLimitProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean expensive = isExpensive(request);
        int allowance = expensive ? properties.enginePerMinute() : properties.readsPerMinute();
        String key = (expensive ? "engine:" : "read:") + callerOf(request);

        sweepOccasionally();

        Window window = windows.computeIfAbsent(key, ignored -> new Window());
        long retryAfterSeconds = window.tryConsume(allowance, properties.window());
        if (retryAfterSeconds < 0) {
            chain.doFilter(request, response);
            return;
        }

        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                expensive
                        ? "Too many searches. The engine is shared; try again shortly."
                        : "Too many requests. Try again shortly.");
        detail.setTitle("Too many requests");
        detail.setType(URI.create(TYPE));
        detail.setProperty("retryAfterSeconds", retryAfterSeconds);
        detail.setProperty("allowancePerMinute", allowance);

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        json.writeValue(response.getOutputStream(), detail);
    }

    /** Anything that makes the engine think. Reads and deletes are cheap and share the larger budget. */
    private static boolean isExpensive(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.endsWith("/analysis")) {
            return true;
        }
        return "POST".equals(request.getMethod()) && (path.endsWith("/moves") || path.equals("/api/games"));
    }

    /**
     * The caller's address, preferring the proxy's forwarded value.
     *
     * <p>Only the first entry is used: the rest are whatever earlier hops claimed, and a client can put
     * anything it likes at the far end of that list.
     */
    private static String callerOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        String address = request.getRemoteAddr();
        return address == null ? "unknown" : address;
    }

    /**
     * Drops windows nobody has used for a while.
     *
     * <p>Done inline on a request rather than on a scheduler, and at most once a minute. A map keyed by
     * client address grows with the number of distinct callers, which is unbounded for anything on the
     * public internet — without this it is a slow memory leak that only shows up after a crawler visits.
     */
    private void sweepOccasionally() {
        long now = System.nanoTime();
        long previous = lastSweep.get();
        if (now - previous < Duration.ofMinutes(1).toNanos()) {
            return;
        }
        if (!lastSweep.compareAndSet(previous, now)) {
            return;
        }
        long stale = properties.window().multipliedBy(4).toNanos();
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() > stale);
    }

    /** A fixed window: a count and the moment it began. */
    private static final class Window {

        private long startedAt = System.nanoTime();
        private int used;

        /**
         * @return {@code -1} if the request is allowed, otherwise how many seconds until the window resets
         */
        synchronized long tryConsume(int allowance, Duration length) {
            long now = System.nanoTime();
            long elapsed = now - startedAt;
            if (elapsed >= length.toNanos()) {
                startedAt = now;
                used = 0;
                elapsed = 0;
            }
            if (used < allowance) {
                used++;
                return -1;
            }
            long remaining = length.toNanos() - elapsed;
            return Math.max(1, Duration.ofNanos(remaining).toSeconds());
        }

        synchronized long startedAt() {
            return startedAt;
        }
    }
}
