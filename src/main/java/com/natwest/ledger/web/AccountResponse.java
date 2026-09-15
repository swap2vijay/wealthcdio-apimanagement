package com.natwest.ledger.web;

import com.natwest.ledger.domain.Account;

import java.math.BigDecimal;

/**
 * An account as returned to a caller.
 *
 * <p>A separate type from {@link Account} on purpose. Serialising the aggregate directly would let
 * an internal field change silently alter the public contract, and would expose the mutable model
 * to the outside world. The mapping is one static method, which is a small price for being able to
 * refactor the domain without breaking clients.
 *
 * <p>Amount and currency are separate fields rather than a formatted string like {@code "10.00 GBP"},
 * so a client never has to parse money back out of prose.
 */
public record AccountResponse(String accountId, String holderName, BigDecimal balance, String currency) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.id().value(),
                account.holderName(),
                account.balance().amount(),
                account.balance().currency().getCurrencyCode());
    }
}
