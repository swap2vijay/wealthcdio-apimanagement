package com.natwest.ledger.exception;

import com.natwest.ledger.client.ComplianceAssessment;

import java.util.Map;

/**
 * Raised when compliance screened a transfer and refused it.
 *
 * <p>A definitive answer, so the caller should not retry. Reported as 422: the request was
 * well-formed and fully understood, and a rule declined it.
 */
public class ComplianceRejectedException extends LedgerException {

    public ComplianceRejectedException(ComplianceAssessment assessment) {
        super(ErrorCode.COMPLIANCE_REJECTED,
                "Transfer %s was refused by compliance screening (%s)"
                        .formatted(assessment.reference().value(), assessment.reason()),
                Map.of("reference", assessment.reference().value(),
                        "reason", assessment.reason(),
                        "retryable", false));
    }
}

