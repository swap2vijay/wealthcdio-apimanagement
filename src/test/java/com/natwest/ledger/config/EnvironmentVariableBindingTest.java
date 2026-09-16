package com.natwest.ledger.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.time.Duration;
import java.util.Currency;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the environment variable names used by the deployment artefacts.
 *
 * <p>The Dockerfiles, docker-compose.yml and the Helm ConfigMaps configure this service purely
 * through environment variables, and nothing else in the build exercises those files - there is no
 * cluster and no Docker daemon available here, so they are reviewed rather than run. Their only
 * safeguard against a name that binds to nothing is this class.
 *
 * <p>Getting a name wrong is quiet rather than loud. Every property here has a default, so an
 * unmatched variable does not fail startup: the service simply keeps talking to
 * {@code localhost:8081}, which in a cluster presents as every transfer failing screening with a
 * connection refused, for a reason nowhere near the mistake.
 *
 * <p>Two spellings are asserted because Spring accepts both, and that turned out to be worth
 * establishing by experiment rather than assuming. For a property such as
 * {@code ledger.compliance.base-url}, the environment mapper looks for the canonical name with the
 * hyphen removed ({@code LEDGER_COMPLIANCE_BASEURL}) and also a legacy name with the hyphen replaced
 * by an underscore ({@code LEDGER_COMPLIANCE_BASE_URL}). The documented advice is to avoid
 * underscores inside a property name, which reads as though the second form would silently fail - it
 * does not. The compose file and ConfigMap use the readable underscored form, so this test is what
 * justifies that choice instead of leaving it to luck.
 */
@DisplayName("Environment variable binding (the contract with Docker and Helm)")
class EnvironmentVariableBindingTest {

    /**
     * Binds as though the given map were the process environment.
     *
     * <p>The property source has to be a {@link SystemEnvironmentPropertySource} named
     * {@code systemEnvironment}: that is how Spring recognises a source whose keys need the
     * environment-variable mapper rather than being matched literally. A plain map property source
     * would compare keys verbatim and the test would prove nothing about environment binding.
     *
     * <p>The real {@code systemEnvironment} source is replaced rather than added to, so an
     * environment variable that happens to be set on the developer's machine or the build agent
     * cannot influence the result.
     */
    private static Binder binderWithEnvironment(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        return Binder.get(environment);
    }

    @Nested
    @DisplayName("ledger.compliance")
    class ComplianceClient {

        private static ComplianceClientProperties bind(Map<String, Object> variables) {
            return binderWithEnvironment(variables)
                    .bind("ledger.compliance", Bindable.of(ComplianceClientProperties.class))
                    .get();
        }

        @Test
        @DisplayName("LEDGER_COMPLIANCE_BASEURL sets the compliance base URL")
        void canonicalBaseUrlBinds() {
            assertThat(bind(Map.of("LEDGER_COMPLIANCE_BASEURL", "http://compliance-service:8081")).baseUrl())
                    .isEqualTo("http://compliance-service:8081");
        }

        @Test
        @DisplayName("LEDGER_COMPLIANCE_BASE_URL also works - this is the form the deployment files use")
        void underscoredBaseUrlBinds() {
            assertThat(bind(Map.of("LEDGER_COMPLIANCE_BASE_URL", "http://compliance-service:8081")).baseUrl())
                    .isEqualTo("http://compliance-service:8081");
        }

        @Test
        @DisplayName("a name that is neither spelling binds to nothing, silently")
        void anUnrecognisedNameBindsNothing() {
            // The failure mode this whole class exists to catch: plausible, unmatched, and quiet.
            // No exception, no warning, and the property keeps its default.
            BindResult<ComplianceClientProperties> result =
                    binderWithEnvironment(Map.of("LEDGER_COMPLIANCE_URL", "http://compliance-service:8081"))
                            .bind("ledger.compliance", Bindable.of(ComplianceClientProperties.class));

            assertThat(result.isBound()).isFalse();
        }

        @Test
        @DisplayName("LEDGER_COMPLIANCE_CONNECT_TIMEOUT and _READ_TIMEOUT set the client timeouts")
        void timeoutsBind() {
            ComplianceClientProperties properties = bind(Map.of(
                    "LEDGER_COMPLIANCE_CONNECT_TIMEOUT", "500ms",
                    "LEDGER_COMPLIANCE_READ_TIMEOUT", "2s"));

            // Durations parse from the suffixed form, so the deployment files can stay readable
            // rather than expressing everything in bare milliseconds.
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(2));
        }
    }

    @Nested
    @DisplayName("ledger")
    class Ledger {

        @Test
        @DisplayName("LEDGER_BASE_CURRENCY sets the base currency")
        void baseCurrencyBinds() {
            LedgerProperties properties = binderWithEnvironment(Map.of("LEDGER_BASE_CURRENCY", "GBP"))
                    .bind("ledger", Bindable.of(LedgerProperties.class))
                    .get();

            assertThat(properties.currency()).isEqualTo(Currency.getInstance("GBP"));
        }
    }
}

