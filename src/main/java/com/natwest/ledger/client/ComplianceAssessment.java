package com.natwest.ledger.client;

import com.natwest.ledger.model.TransactionReference;

import java.util.Objects;

/**
 * The result of asking compliance about one transfer.
 *
 * @param reference the transfer this assessment concerns, so it can be correlated across services
 * @param outcome   approved, refused, or unknown
 * @param reason    a machine-readable reason when not approved; null when approved
 */
public record ComplianceAssessment(TransactionReference reference, ComplianceOutcome outcome, String reason) {

    public ComplianceAssessment {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome != ComplianceOutcome.APPROVED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(
                    "A %s assessment must state a reason".formatted(outcome));
        }
    }

    public static ComplianceAssessment approved(TransactionReference reference) {
        return new ComplianceAssessment(reference, ComplianceOutcome.APPROVED, null);
    }

    public static ComplianceAssessment rejected(TransactionReference reference, String reason) {
        return new ComplianceAssessment(reference, ComplianceOutcome.REJECTED, reason);
    }

    public static ComplianceAssessment unavailable(TransactionReference reference, String reason) {
        return new ComplianceAssessment(reference, ComplianceOutcome.UNAVAILABLE, reason);
    }

    public boolean isApproved() {
        return outcome == ComplianceOutcome.APPROVED;
    }
}

