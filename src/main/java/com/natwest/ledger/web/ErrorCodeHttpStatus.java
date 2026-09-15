package com.natwest.ledger.web;

import com.natwest.ledger.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Translates a business {@link ErrorCode} into an HTTP status.
 *
 * <p>This is the only place in the service that knows both vocabularies. Keeping the mapping here
 * rather than on the enum means the domain stays free of transport concerns, and the same error
 * codes could be surfaced over a different protocol without change.
 *
 * <p><b>Written as an exhaustive switch with no {@code default}.</b> That is deliberate: adding a
 * new {@link ErrorCode} then fails compilation until somebody decides what it means over HTTP. A
 * {@code default} branch would silently report every future error as a 500 or a 400, and the
 * omission would only be noticed by a client.
 */
final class ErrorCodeHttpStatus {

    private ErrorCodeHttpStatus() {
    }

    static HttpStatus of(ErrorCode errorCode) {
        return switch (errorCode) {

            // The request is wrong on its face: a malformed amount, an impossible id, a transfer
            // to oneself. The caller must change the request.
            case INVALID_AMOUNT,
                 CURRENCY_MISMATCH,
                 SAME_ACCOUNT_TRANSFER,
                 INVALID_ACCOUNT_ID,
                 MALFORMED_REQUEST -> HttpStatus.BAD_REQUEST;

            // 422, not 400. The request is well-formed and perfectly understood - it is the state
            // of the account that forbids it. A client cannot fix this by correcting its syntax,
            // and distinguishing the two lets callers tell "I sent something invalid" apart from
            // "the customer needs more money", which need very different handling.
            case INSUFFICIENT_FUNDS -> HttpStatus.UNPROCESSABLE_ENTITY;

            case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;

            // Both are conflicts with the current state of a resource, so both are 409. They differ
            // in what a client should do next: a duplicate account will never succeed however many
            // times it is retried, whereas a concurrent modification very likely will. The distinct
            // error codes carry that difference, which is precisely why status alone is not enough.
            case DUPLICATE_ACCOUNT, CONCURRENT_MODIFICATION -> HttpStatus.CONFLICT;

            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
