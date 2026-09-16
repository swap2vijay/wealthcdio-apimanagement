package com.natwest.ledger.exception;

import com.natwest.ledger.client.ComplianceAssessment;

import java.util.Map;

/**
 * Raised when compliance could not be reached, so the transfer was abandoned.
 *
 * <p><b>The service fails closed, and this exception is that policy made visible.</b> When screening
 * cannot be performed there are only two options: allow the payment unscreened, or refuse it. For a
 * bank the choice is not close - a control that silently switches itself off under load is not a
 * control, and "the compliance service was down" is not a defence. So an outage in screening stops
 * transfers rather than waving them through.
 *
 * <p>Reported as 503 with {@code retryable: true}, because nothing was applied and the condition is
 * expected to pass. Deposits and withdrawals are unaffected - only transfers require screening, so an
 * outage here does not take the whole service down with it.
 */
public class ComplianceUnavailableException extends LedgerException {

    public ComplianceUnavailableException(ComplianceAssessment assessment) {
        super(ErrorCode.COMPLIANCE_UNAVAILABLE,
                "Transfer %s was not attempted because compliance screening is unavailable (%s)"
                        .formatted(assessment.reference().value(), assessment.reason()),
                Map.of("reference", assessment.reference().value(),
                        "reason", assessment.reason(),
                        "retryable", true));
    }
}

