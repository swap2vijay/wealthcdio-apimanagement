package com.natwest.ledger.exception;

import com.natwest.ledger.model.AccountId;

import java.util.Map;

/** Raised when an account identifier is blank, over-long, or contains unsupported characters. */
public class InvalidAccountIdException extends LedgerException {

    public InvalidAccountIdException(String message, String offendingValue) {
        super(ErrorCode.INVALID_ACCOUNT_ID, message, Map.of("accountId", String.valueOf(offendingValue)));
    }
}

