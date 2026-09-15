package com.natwest.ledger.web;

import com.natwest.ledger.domain.Money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;

/**
 * Turns the loosely-typed money fields of a request into a {@link Money}.
 *
 * <p>A request carries an amount and an optional currency code, both of which arrive as whatever
 * JSON happened to contain. This is the one place that reconciles them into a domain value, so the
 * controllers stay free of parsing.
 *
 * <p><b>An unrecognised currency fails here; a recognised but wrong one fails in the domain.</b>
 * {@code "XYZ"} is not a currency at all, so it is a malformed request. {@code "USD"} is a perfectly
 * real currency that this service does not hold, which is a business rule - so it is passed through
 * as a genuine {@code Currency} and refused by {@link com.natwest.ledger.domain.Account}. Rejecting
 * both here would leave the domain's currency guard untested through the API.
 */
final class MoneyRequests {

    private MoneyRequests() {
    }

    /**
     * @param amount       the requested amount; must not be null (bean validation enforces that)
     * @param currencyCode an ISO 4217 code, or null/blank to assume {@code baseCurrency}
     */
    static Money toMoney(BigDecimal amount, String currencyCode, Currency baseCurrency) {
        return Money.of(amount, currencyOf(currencyCode, baseCurrency));
    }

    private static Currency currencyOf(String currencyCode, Currency fallback) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return fallback;
        }
        String normalised = currencyCode.trim().toUpperCase(Locale.ROOT);
        try {
            return Currency.getInstance(normalised);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "'%s' is not a recognised ISO 4217 currency code".formatted(currencyCode));
        }
    }
}
