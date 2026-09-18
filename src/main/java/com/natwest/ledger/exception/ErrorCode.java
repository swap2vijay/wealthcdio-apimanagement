package com.natwest.ledger.exception;

/**
 * The catalogue of business error codes this service can report.
 *
 * <p>These codes are part of the service's public contract: clients are expected to branch on
 * {@link #code()}, never on the human-readable message, and never on the HTTP status alone
 * (several distinct business failures legitimately share a status).
 *
 * <p>Deliberately free of any HTTP concepts. Translating a business failure into a transport
 * status is a presentation concern and lives in the web layer, so the domain never has to know
 * that HTTP exists.
 *
 * <p>Ranges are stable and additive:
 * <ul>
 *   <li>{@code 1xxx} - the caller asked for something the domain rules forbid</li>
 *   <li>{@code 2xxx} - the referenced resource does not exist, or already does</li>
 *   <li>{@code 4xxx} - the request could not be understood</li>
 *   <li>{@code 9xxx} - the service itself failed; the caller did nothing wrong</li>
 * </ul>
 */
public enum ErrorCode {

    /* ---------- 1xxx: domain rule violations ---------- */

    INVALID_AMOUNT("LDG-1001", "The supplied monetary amount is not valid"),
    CURRENCY_MISMATCH("LDG-1002", "The supplied amount is in a different currency to the account"),
    INSUFFICIENT_FUNDS("LDG-1003", "The account does not hold enough funds for this withdrawal"),
    SAME_ACCOUNT_TRANSFER("LDG-1004", "A transfer must be between two different accounts"),
    INVALID_ACCOUNT_ID("LDG-1005", "The supplied account identifier is not valid"),

    /* ---------- 2xxx: the resource does not exist, or already does ---------- */

    ACCOUNT_NOT_FOUND("LDG-2001", "No account exists with the supplied identifier"),
    DUPLICATE_ACCOUNT("LDG-2002", "An account already exists with the supplied identifier"),

    /**
     * Another request changed the same account first, so this one was abandoned rather than allowed
     * to overwrite it. Safe and sensible to retry.
     */
    CONCURRENT_MODIFICATION("LDG-2003", "The account was modified concurrently; the request was not applied"),

    /* ---------- 4xxx: the request itself was malformed ---------- */

    MALFORMED_REQUEST("LDG-4001", "The request could not be read or failed validation"),

    /* ---------- 9xxx: the service failed, not the caller ---------- */

    INTERNAL_ERROR("LDG-9001", "The service failed to process the request");

    private final String code;
    private final String title;

    ErrorCode(String code, String title) {
        this.code = code;
        this.title = title;
    }

    /** The stable, machine-readable code clients should branch on, e.g. {@code LDG-1003}. */
    public String code() {
        return code;
    }

    /** A short, non-contextual description of the failure class. */
    public String title() {
        return title;
    }
}

