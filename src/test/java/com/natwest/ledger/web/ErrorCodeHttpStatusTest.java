package com.natwest.ledger.web;

import com.natwest.ledger.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies how business failures are reported over HTTP.
 *
 * <p>The distinctions asserted here are the ones clients depend on to decide what to do next: retry,
 * correct the request, or tell the customer their balance is short.
 */
@DisplayName("Mapping error codes to HTTP status")
class ErrorCodeHttpStatusTest {

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("every error code has a deliberate status, so none can fall through unclassified")
    void everyErrorCodeIsMapped(ErrorCode errorCode) {
        assertThat(ErrorCodeHttpStatus.of(errorCode)).isNotNull();
    }

    @Test
    @DisplayName("reports a request that is wrong on its face as 400")
    void malformedRequestsAreBadRequest() {
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.INVALID_AMOUNT)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.INVALID_ACCOUNT_ID)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.SAME_ACCOUNT_TRANSFER)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.CURRENCY_MISMATCH)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.MALFORMED_REQUEST)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("reports insufficient funds as 422, not 400: the request was understood, the balance refused it")
    void insufficientFundsIsUnprocessable() {
        // The distinction matters to a client: a 400 means "fix your request", whereas a 422 here
        // means "the request was fine, the customer needs more money". Collapsing both into 400
        // would leave callers unable to tell a bug from an ordinary business outcome.
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.INSUFFICIENT_FUNDS))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @DisplayName("reports a missing account as 404")
    void missingAccountIsNotFound() {
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.ACCOUNT_NOT_FOUND)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("reports a duplicate account as 409, since retrying unchanged can never succeed")
    void duplicateAccountIsConflict() {
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.DUPLICATE_ACCOUNT)).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("reports a service fault as 500, keeping caller error and service error distinct")
    void serviceFailureIsInternalServerError() {
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.INTERNAL_ERROR))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("reports a failed reversal as 500, not 503, so a retry is not invited")
    void failedCompensationIsNotRetryable() {
        // A retry would debit the account a second time while the first debit is still stranded.
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.TRANSFER_COMPENSATION_FAILED))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .isNotEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("refuses a transfer as 503 when compliance cannot be reached, since the fault is ours")
    void complianceUnavailableIsServiceUnavailable() {
        // 503 rather than 500: it tells the caller the condition is transient and worth retrying, and
        // it is the status load balancers and clients already understand as "try again shortly".
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.COMPLIANCE_UNAVAILABLE))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("reports a compliance refusal as 422, the same shape as any other rule saying no")
    void complianceRejectedIsUnprocessable() {
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.COMPLIANCE_REJECTED))
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    /**
     * Codes where the failure is on this service's side of the boundary rather than the caller's.
     *
     * <p>Listed explicitly so that adding an error code forces a deliberate answer to "whose fault is
     * this?" - the question that decides whether a client should fix its request or retry unchanged.
     */
    private static final Set<ErrorCode> NOT_THE_CALLERS_FAULT = EnumSet.of(
            ErrorCode.INTERNAL_ERROR,
            ErrorCode.COMPLIANCE_UNAVAILABLE,
            ErrorCode.TRANSFER_COMPENSATION_FAILED);

    @Test
    @DisplayName("never blames the caller for our failure, nor us for the caller's")
    void clientAndServerFaultsAreNotConfused() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            HttpStatus status = ErrorCodeHttpStatus.of(errorCode);
            boolean ourFault = NOT_THE_CALLERS_FAULT.contains(errorCode);

            assertThat(status.is5xxServerError())
                    .as("%s should be reported as a %s fault", errorCode, ourFault ? "server" : "client")
                    .isEqualTo(ourFault);

            assertThat(status.is4xxClientError())
                    .as("%s must be exactly one of a client or server fault", errorCode)
                    .isEqualTo(!ourFault);
        }
    }

    @Test
    @DisplayName("distinguishes a refusal we can explain from a dependency we cannot reach")
    void refusalAndOutageAreDifferentStatuses() {
        // Collapsing these would leave a caller unable to tell "this payment is not allowed" from
        // "ask again in a minute" - two situations needing opposite handling.
        assertThat(ErrorCodeHttpStatus.of(ErrorCode.COMPLIANCE_REJECTED))
                .isNotEqualTo(ErrorCodeHttpStatus.of(ErrorCode.COMPLIANCE_UNAVAILABLE));
    }
}
