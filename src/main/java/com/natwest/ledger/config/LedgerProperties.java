package com.natwest.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Currency;

/**
 * Externalised settings for the ledger.
 *
 * @param baseCurrency the single currency this service operates in. Requests may omit a currency,
 *                     in which case this one is assumed; a request naming a different currency is
 *                     rejected rather than converted.
 */
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(String baseCurrency) {

    private static final String DEFAULT_BASE_CURRENCY = "GBP";

    public LedgerProperties {
        baseCurrency = (baseCurrency == null || baseCurrency.isBlank())
                ? DEFAULT_BASE_CURRENCY
                : baseCurrency.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * The configured currency.
     *
     * @throws IllegalArgumentException at startup if the configured code is not a real currency,
     *                                  which is the right moment to fail rather than on first request
     */
    public Currency currency() {
        return Currency.getInstance(baseCurrency);
    }
}

