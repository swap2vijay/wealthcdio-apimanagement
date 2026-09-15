package com.natwest.ledger.application;

import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.error.ErrorCode;
import com.natwest.ledger.error.LedgerException;

import java.util.Map;

/**
 * Raised when an operation names an account that does not exist.
 *
 * <p>An application-layer concern rather than a domain one: the {@code Account} aggregate cannot
 * know about accounts other than itself, so "does this exist?" is a question only the repository
 * can answer.
 */
public class AccountNotFoundException extends LedgerException {

    public AccountNotFoundException(AccountId accountId) {
        super(ErrorCode.ACCOUNT_NOT_FOUND,
                "No account exists with id %s".formatted(accountId.value()),
                Map.of("accountId", accountId.value()));
    }
}
