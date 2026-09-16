package com.natwest.ledger.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Makes the clock an injected dependency rather than ambient global state.
 *
 * <p>Nothing in this service calls {@code Instant.now()}. Every timestamp comes from this bean,
 * which means a test can substitute {@link Clock#fixed} and assert on exact ledger timestamps
 * instead of asserting loosely that something happened "roughly now".
 */
@Configuration
public class TimeConfiguration {

    /**
     * UTC, deliberately.
     *
     * <p>Ledger timestamps are instants, not local wall-clock readings. Recording them in a zone
     * that observes daylight saving would produce an hour each year that repeats and an hour that
     * never happens - and transaction ordering across that boundary becomes ambiguous. Formatting
     * for a customer's timezone is a presentation concern.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}

