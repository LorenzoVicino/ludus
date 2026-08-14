package io.github.lorenzovicino.ludus.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.lorenzovicino.ludus.server.config.RateLimitProperties;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The budget, and specifically the part that would be easy to get wrong: that a cheap request and an
 * expensive one do not share one.
 *
 * <p>A single allowance would mean either a limit loose enough to let one caller hold the engine
 * indefinitely, or one tight enough that a browser drawing a board runs out of it. Both are worse than
 * having two.
 */
class RateLimitFilterTest {

    private static final String ADDRESS = "203.0.113.7";

    private RateLimitFilter filterWith(int reads, int engine) {
        return new RateLimitFilter(
                new RateLimitProperties(true, reads, engine, Duration.ofMinutes(1)), new ObjectMapper());
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ADDRESS);
        return request;
    }

    private int call(RateLimitFilter filter, String method, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = new MockFilterChain();
        filter.doFilter(request(method, path), response, chain);
        return response.getStatus();
    }

    @Test
    @DisplayName("requests are allowed up to the allowance and refused after it")
    void theAllowanceIsEnforced() throws Exception {
        RateLimitFilter filter = filterWith(100, 3);

        for (int i = 1; i <= 3; i++) {
            assertEquals(HttpStatus.OK.value(), call(filter, "POST", "/api/games/x/moves"),
                    "call " + i + " is within the allowance of three");
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(),
                call(filter, "POST", "/api/games/x/moves"), "the fourth is not");
    }

    @Test
    @DisplayName("using up the engine budget does not stop the board being read")
    void thebudgetsAreSeparate() throws Exception {
        RateLimitFilter filter = filterWith(100, 1);

        assertEquals(HttpStatus.OK.value(), call(filter, "POST", "/api/games/x/moves"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), call(filter, "POST", "/api/games/x/moves"));

        // The whole reason there are two budgets: a client that has exhausted its searches must still be
        // able to read the position it is looking at.
        assertEquals(HttpStatus.OK.value(), call(filter, "GET", "/api/games/x"));
    }

    @Test
    @DisplayName("a refusal says how long to wait, in a header and in the body")
    void refusalsAreActionable() throws Exception {
        RateLimitFilter filter = filterWith(100, 1);
        call(filter, "POST", "/api/games", null);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request("POST", "/api/games"), response, new MockFilterChain());

        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), response.getStatus());
        assertTrue(Integer.parseInt(response.getHeader("Retry-After")) >= 1,
                "\"try later\" without a number is not information");
        assertTrue(response.getContentAsString().contains("allowancePerMinute"),
                "the body should say what the allowance was");
        assertEquals("application/problem+json", response.getContentType());
    }

    @Test
    @DisplayName("callers have their own budgets")
    void oneCallerDoesNotSpendAnother() throws Exception {
        RateLimitFilter filter = filterWith(100, 1);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(),
                secondCallFrom(filter, ADDRESS), "the same address runs out");

        MockHttpServletRequest other = new MockHttpServletRequest("POST", "/api/games/x/moves");
        other.setRemoteAddr("198.51.100.4");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(other, response, new MockFilterChain());
        assertEquals(HttpStatus.OK.value(), response.getStatus(), "a different one does not");
    }

    @Test
    @DisplayName("the proxy's forwarded address is preferred over the connection's")
    void forwardedAddressIsUsed() throws Exception {
        // Behind a proxy every connection comes from the same place, so without this one visitor would
        // spend everybody's budget.
        RateLimitFilter filter = filterWith(100, 1);

        MockHttpServletRequest first = new MockHttpServletRequest("POST", "/api/games/x/moves");
        first.setRemoteAddr("10.0.0.1");
        first.addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, new MockFilterChain());
        assertEquals(HttpStatus.OK.value(), firstResponse.getStatus());

        MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/games/x/moves");
        second.setRemoteAddr("10.0.0.1");
        second.addHeader("X-Forwarded-For", "198.51.100.2, 10.0.0.1");
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, new MockFilterChain());
        assertEquals(HttpStatus.OK.value(), secondResponse.getStatus(),
                "a different forwarded client has its own budget even from the same proxy");
    }

    @Test
    @DisplayName("nothing outside /api is limited, and nothing at all when it is off")
    void scopeAndSwitch() throws Exception {
        RateLimitFilter filter = filterWith(1, 1);
        assertEquals(HttpStatus.OK.value(), call(filter, "GET", "/"));
        assertEquals(HttpStatus.OK.value(), call(filter, "GET", "/"), "the page is not rationed");

        RateLimitFilter off = new RateLimitFilter(
                new RateLimitProperties(false, 1, 1, Duration.ofMinutes(1)), new ObjectMapper());
        assertEquals(HttpStatus.OK.value(), call(off, "POST", "/api/games/x/moves"));
        assertEquals(HttpStatus.OK.value(), call(off, "POST", "/api/games/x/moves"));
    }

    private int secondCallFrom(RateLimitFilter filter, String address) throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/games/x/moves");
            request.setRemoteAddr(address);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            if (i == 1) {
                return response.getStatus();
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private int call(RateLimitFilter filter, String method, String path, Object ignored)
            throws Exception {
        return call(filter, method, path);
    }
}
