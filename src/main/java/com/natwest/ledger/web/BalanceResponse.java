package com.natwest.ledger.web;

import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.Money;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The balance of an account at a point in time.
 *
 * <p>Carries {@code asOf} because a balance is a reading, not a permanent fact - it may already have
 * changed by the time the caller reads the response. Stamping it lets a client reason about
 * staleness and makes two conflicting screenshots explainable.
 */
public record BalanceResponse(String accountId, BigDecimal balance, String currency, Instant asOf) {

    public static BalanceResponse of(AccountId accountId, Money balance, Instant asOf) {
        return new BalanceResponse(
                accountId.value(),
                balance.amount(),
                balance.currency().getCurrencyCode(),
                asOf);
    }
}
