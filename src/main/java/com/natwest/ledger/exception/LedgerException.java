package com.natwest.ledger.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Base type for every failure this service raises deliberately.
 *
 * <p>Two decisions worth calling out:
 *
 * <p><b>Unchecked.</b> These exceptions are not recoverable by the immediate caller - a REST
 * handler cannot conjure funds into an account. Making them checked would force
 * {@code throws} noise through every layer for no behavioural gain.
 *
 * <p><b>Carries a code, not a status.</b> Every instance names an {@link ErrorCode}, which lets
 * the web layer translate failures into transport responses in one place rather than each
 * throw site guessing at an HTTP status.
 */
public abstract class LedgerException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    protected LedgerException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of(), null);
    }

    protected LedgerException(ErrorCode errorCode, String message, Map<String, Object> details) {
        this(errorCode, message, details, null);
    }

    protected LedgerException(ErrorCode errorCode,
                              String message,
                              Map<String, Object> details,
                              Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNullElseGet(details, Map::of)));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * Structured context describing this particular failure, safe to return to the caller.
     *
     * <p>Exists so that responses can stay machine-usable without clients resorting to parsing
     * the message string: an insufficient-funds failure reports the balance and the shortfall as
     * fields rather than burying them in prose.
     */
    public Map<String, Object> details() {
        return details;
    }
}

