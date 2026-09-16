package com.natwest.ledger.exception;

import com.natwest.ledger.model.Account;
import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.model.Money;

import java.util.Map;

/**
 * Raised when a withdrawal or outbound transfer would drive an account below zero.
 *
 * <p>This service permits no overdraft whatsoever: the guard is {@code balance >= amount}, not
 * {@code balance - amount >= someNegativeLimit}. An overdraft facility is a per-account product
 * feature, and modelling it as a hard zero floor now keeps the rule honest and easy to relax
 * later (see the README).
 *
 * <p>Reports the shortfall as structured data so a caller can tell the customer how much they
 * are short without re-deriving it.
 */
public class InsufficientFundsException extends LedgerException {

    public InsufficientFundsException(AccountId accountId, Money balance, Money requested) {
        super(ErrorCode.INSUFFICIENT_FUNDS,
                "Account %s holds %s which cannot cover a withdrawal of %s (short by %s)"
                        .formatted(accountId.value(), balance, requested, requested.minus(balance)),
                Map.of("accountId", accountId.value(),
                        "balance", balance.toPlainString(),
                        "requested", requested.toPlainString(),
                        "shortfall", requested.minus(balance).toPlainString(),
                        "currency", balance.currency().getCurrencyCode()));
    }
}

