package com.natwest.ledger.domain;

import com.natwest.ledger.error.ErrorCode;
import com.natwest.ledger.error.LedgerException;

import java.util.Currency;
import java.util.Map;

/**
 * Raised when two amounts in different currencies would otherwise be combined or compared.
 *
 * <p>The service does not perform foreign exchange. Silently treating 10 USD as 10 GBP is the
 * kind of defect that only surfaces in a reconciliation report weeks later, so mixed-currency
 * arithmetic fails loudly at the point of use instead.
 */
public class CurrencyMismatchException extends LedgerException {

    public CurrencyMismatchException(Currency expected, Currency actual) {
        super(ErrorCode.CURRENCY_MISMATCH,
                "Expected an amount in %s but was %s; this service does not convert between currencies"
                        .formatted(expected.getCurrencyCode(), actual.getCurrencyCode()),
                Map.of("expectedCurrency", expected.getCurrencyCode(),
                        "actualCurrency", actual.getCurrencyCode()));
    }
}
