package com.natwest.ledger.config;

import com.natwest.ledger.application.ComplianceGateway;
import com.natwest.ledger.infrastructure.compliance.ComplianceCallFailedException;
import com.natwest.ledger.infrastructure.compliance.ComplianceContractException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies that the resilience settings the service actually runs with are the intended ones.
 *
 * <p>The gateway's own tests use deliberately compressed timings, which means they would pass even if
 * the production configuration were missing, misspelt or silently ignored - a real hazard with
 * YAML-driven config, where a typo in an instance name yields a default-configured breaker rather than
 * an error. This test reads the values from the running context so that cannot go unnoticed.
 */
@SpringBootTest
@DisplayName("The configured resilience settings")
class ComplianceResilienceConfigurationTest {

    private static final String INSTANCE = "compliance";

    @Autowired
    private CircuitBreakerRegistry circuitBreakers;

    @Autowired
    private RetryRegistry retries;

    @Autowired
    private ComplianceClientProperties clientProperties;

    @Autowired
    private ComplianceGateway gateway;

    @Test
    @DisplayName("wire a compliance gateway into the application")
    void gatewayIsWired() {
        assertThat(gateway).isNotNull();
    }

    @Test
    @DisplayName("judge the dependency on a sample of calls, not on the first failure")
    void circuitBreakerIsConfigured() {
        CircuitBreakerConfig config = circuitBreakers.circuitBreaker(INSTANCE).getCircuitBreakerConfig();

        assertThat(config.getSlidingWindowType()).isEqualTo(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED);
        assertThat(config.getSlidingWindowSize()).isEqualTo(10);
        assertThat(config.getMinimumNumberOfCalls())
                .as("without a minimum sample, one failed call is a 100%% failure rate")
                .isEqualTo(5);
        assertThat(config.getFailureRateThreshold()).isEqualTo(50.0f);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        assertThat(config.getWaitIntervalFunctionInOpenState().apply(1))
                .isEqualTo(Duration.ofSeconds(10).toMillis());
    }

    @Test
    @DisplayName("do not let our own malformed request trip the circuit")
    void circuitBreakerIgnoresOurOwnFaults() {
        CircuitBreakerConfig config = circuitBreakers.circuitBreaker(INSTANCE).getCircuitBreakerConfig();

        assertThat(config.getIgnoreExceptionPredicate().test(new ComplianceContractException("bad payload")))
                .as("a 400 caused by us is not evidence that compliance is unwell")
                .isTrue();
        assertThat(config.getIgnoreExceptionPredicate().test(new ComplianceCallFailedException("503")))
                .as("a genuine dependency failure must be counted")
                .isFalse();
    }

    @Test
    @DisplayName("retry a failing call a bounded number of times, with backoff")
    void retryIsConfigured() {
        RetryConfig config = retries.retry(INSTANCE).getRetryConfig();

        assertThat(config.getMaxAttempts())
                .as("three attempts in total, not three retries on top of the first call")
                .isEqualTo(3);
        assertThat(config.getIntervalBiFunction().apply(1, null)).isEqualTo(200L);
        assertThat(config.getIntervalBiFunction().apply(2, null))
                .as("exponential backoff, so struggling dependencies are not hit in lockstep")
                .isEqualTo(400L);
    }

    @Test
    @DisplayName("retry genuine failures but never a request we got wrong")
    void retrySkipsOurOwnFaults() {
        RetryConfig config = retries.retry(INSTANCE).getRetryConfig();

        assertThat(config.getExceptionPredicate().test(new ComplianceCallFailedException("503"))).isTrue();
        assertThat(config.getExceptionPredicate().test(new ComplianceContractException("bad payload")))
                .as("retrying a 400 just produces another 400")
                .isFalse();
    }

    @Test
    @DisplayName("never allow an unlimited timeout, which would defeat the breaker entirely")
    void timeoutsAreBounded() {
        assertThat(clientProperties.connectTimeout()).isPositive();
        assertThat(clientProperties.readTimeout()).isPositive();
        assertThat(clientProperties.readTimeout())
                .as("screening is a synchronous rule check with no database behind it")
                .isLessThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(clientProperties.baseUrl()).isNotBlank();
    }
}
