package com.natwest.ledger.application;

import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.error.ErrorCode;
import com.natwest.ledger.error.LedgerException;

import java.util.Map;

/**
 * Raised when opening an account whose identifier is already taken.
 *
 * <p>Enforces the "unique ids" requirement. Note that because {@link AccountId} normalises case,
 * opening {@code acc-1} when {@code ACC-1} exists is correctly refused as a duplicate rather than
 * quietly creating a second account that only differs in presentation.
 */
public class DuplicateAccountException extends LedgerException {

    public DuplicateAccountException(AccountId accountId) {
        super(ErrorCode.DUPLICATE_ACCOUNT,
                "An account already exists with id %s".formatted(accountId.value()),
                Map.of("accountId", accountId.value()));
    }
}
