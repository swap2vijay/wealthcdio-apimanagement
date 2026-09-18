package com.natwest.ledger.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Currency;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the environment variable names this service actually binds to.
 *
 * <p>Getting a name wrong is quiet rather than loud. {@code LedgerProperties} has a default, so an
 * unmatched variable does not fail startup - the service simply keeps the compiled-in currency, for a
 * reason nowhere near the mistake.
 */
@DisplayName("Environment variable binding")
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
