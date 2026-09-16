package com.natwest.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How to reach the compliance service.
 *
 * <p><b>Both timeouts have defaults and neither may be unlimited.</b> A dependency that hangs is far
 * more dangerous than one that fails: with no read timeout, every waiting request holds a thread, the
 * pool drains, and this service stops answering requests that have nothing to do with transfers. It
 * also defeats the circuit breaker, which can only trip on failures it actually observes - a call that
 * never returns is never recorded as anything.
 *
 * <p>The read timeout is short by design. Screening is a synchronous rule evaluation with no database
 * behind it; if it has not answered in two seconds it is not about to.
 *
 * @param baseUrl        root URL of the compliance service
 * @param connectTimeout how long to wait for a connection
 * @param readTimeout    how long to wait for an answer once connected
 */
@ConfigurationProperties(prefix = "ledger.compliance")
public record ComplianceClientProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {

    private static final String DEFAULT_BASE_URL = "http://localhost:8081";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(2);

    public ComplianceClientProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        connectTimeout = orDefault(connectTimeout, DEFAULT_CONNECT_TIMEOUT);
        readTimeout = orDefault(readTimeout, DEFAULT_READ_TIMEOUT);
    }

    private static Duration orDefault(Duration configured, Duration fallback) {
        // Zero or negative means "wait forever" to the underlying client, which is exactly the
        // condition this class exists to prevent, so it is treated as absent.
        return (configured == null || configured.isZero() || configured.isNegative()) ? fallback : configured;
    }
}

