package com.natwest.ledger.exception;

import com.natwest.ledger.model.Account;
import com.natwest.ledger.model.AccountId;

import java.util.Map;

/**
 * Raised when a transfer names the same account as both source and destination.
 *
 * <p>Such a transfer is a no-op on the balance, so it would be tempting to allow it. It is
 * rejected because it is almost always a client defect, and because permitting it would write a
 * misleading pair of ledger entries suggesting money moved when none did.
 */
public class SameAccountTransferException extends LedgerException {

    public SameAccountTransferException(AccountId accountId) {
        super(ErrorCode.SAME_ACCOUNT_TRANSFER,
                "Cannot transfer from account %s to itself".formatted(accountId.value()),
                Map.of("accountId", accountId.value()));
    }
}

