package io.github.lorenzovicino.ludus.server.config;

import io.github.lorenzovicino.ludus.server.engine.EngineProperties;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "ludus",
        version = "0.1.0",
        description = "Play a chess engine written from scratch in Java.",
        license = @License(name = "MIT", url = "https://opensource.org/licenses/MIT")))
public class ServerConfig {

    /**
     * Injected rather than called statically, so that anything time-dependent can be tested without
     * waiting for the clock to cooperate.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Runs streaming searches off the request thread.
     *
     * <p>Sized to the engine pool, and that is not a coincidence: more threads here could not do more
     * work, because every one of them would end up waiting on the same fixed set of engines. It would
     * only move the queue from a place with a timeout and a 503 to a place without either.
     *
     * <p>The queue is a small one and rejection is <em>abortive</em> on purpose. The alternative,
     * {@code CallerRunsPolicy}, would run a multi-second search on a servlet thread — which is precisely
     * the thread that must stay free to answer the cheap requests.
     */
    @Bean
    public Executor analysisExecutor(EngineProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.poolSize());
        executor.setMaxPoolSize(properties.poolSize());
        executor.setQueueCapacity(properties.poolSize());
        executor.setThreadNamePrefix("analysis-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
