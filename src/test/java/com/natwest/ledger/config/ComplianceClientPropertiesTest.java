package com.natwest.ledger.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies that a missing or nonsensical timeout falls back to a safe value.
 *
 * <p>The dangerous configuration is not a wrong number, it is no number: an absent or zero timeout
 * means "wait forever" to the underlying HTTP client, and a dependency that hangs will drain the
 * request thread pool while never being recorded as a failure. These defaults exist so a deployment
 * with incomplete configuration degrades to safe behaviour rather than to unbounded waiting.
 */
@DisplayName("Compliance client properties")
class ComplianceClientPropertiesTest {

    @Test
    @DisplayName("keep configured values when they are sensible")
    void keepsConfiguredValues() {
        ComplianceClientProperties properties = new ComplianceClientProperties(
                "http://compliance:8081", Duration.ofMillis(300), Duration.ofSeconds(1));

        assertThat(properties.baseUrl()).isEqualTo("http://compliance:8081");
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(300));
        assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("fall back to bounded timeouts when none are configured")
    void defaultsMissingTimeouts() {
        ComplianceClientProperties properties = new ComplianceClientProperties(null, null, null);

        assertThat(properties.connectTimeout()).isPositive();
        assertThat(properties.readTimeout()).isPositive();
        assertThat(properties.baseUrl()).isNotBlank();
    }

    @Test
    @DisplayName("treat a zero timeout as absent, since zero means wait forever")
    void treatsZeroAsAbsent() {
        ComplianceClientProperties properties = new ComplianceClientProperties(
                "http://compliance:8081", Duration.ZERO, Duration.ZERO);

        assertThat(properties.connectTimeout()).isPositive();
        assertThat(properties.readTimeout()).isPositive();
    }

    @Test
    @DisplayName("treat a negative timeout as absent")
    void treatsNegativeAsAbsent() {
        ComplianceClientProperties properties = new ComplianceClientProperties(
                "http://compliance:8081", Duration.ofSeconds(-1), Duration.ofSeconds(-5));

        assertThat(properties.connectTimeout()).isPositive();
        assertThat(properties.readTimeout()).isPositive();
    }

    @Test
    @DisplayName("trim a base URL that arrived with stray whitespace")
    void trimsBaseUrl() {
        assertThat(new ComplianceClientProperties("  http://compliance:8081  ", null, null).baseUrl())
                .isEqualTo("http://compliance:8081");
    }
}

