package com.natwest.ledger.web;

import com.natwest.ledger.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

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
    @DisplayName("never blames the caller for a service fault, nor the service for a caller fault")
    void clientAndServerFaultsAreNotConfused() {
        for (ErrorCode errorCode : ErrorCode.values()) {
            HttpStatus status = ErrorCodeHttpStatus.of(errorCode);
            boolean isServiceFault = errorCode == ErrorCode.INTERNAL_ERROR;

            assertThat(status.is5xxServerError())
                    .as("%s should%s be a server fault", errorCode, isServiceFault ? "" : " not")
                    .isEqualTo(isServiceFault);
        }
    }
}
