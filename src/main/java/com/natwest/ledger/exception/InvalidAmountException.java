package com.natwest.ledger.exception;

import com.natwest.ledger.model.Money;

import java.util.Map;

/**
 * Raised when a monetary amount is unusable: null, non-positive where the operation requires a
 * positive movement, or carrying more precision than the currency can represent.
 */
public class InvalidAmountException extends LedgerException {

    public InvalidAmountException(String message) {
        super(ErrorCode.INVALID_AMOUNT, message);
    }

    public InvalidAmountException(String message, Map<String, Object> details) {
        super(ErrorCode.INVALID_AMOUNT, message, details);
    }

    public static InvalidAmountException notPositive(String operation, Money amount) {
        return new InvalidAmountException(
                "%s requires a positive amount but was %s".formatted(operation, amount),
                Map.of("operation", operation, "amount", amount.toPlainString()));
    }
}

