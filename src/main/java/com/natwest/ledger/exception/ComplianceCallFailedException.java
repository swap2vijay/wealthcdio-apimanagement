package com.natwest.ledger.exception;

/**
 * The compliance service could not be reached, timed out, or returned a server error.
 *
 * <p>The failure the circuit breaker exists to count. Every instance is evidence that the dependency
 * is unwell, and enough of them in a window will open the circuit so that subsequent calls fail fast
 * instead of queueing behind a service that cannot answer.
 */
public class ComplianceCallFailedException extends RuntimeException {

    public ComplianceCallFailedException(String message) {
        super(message);
    }

    public ComplianceCallFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}

