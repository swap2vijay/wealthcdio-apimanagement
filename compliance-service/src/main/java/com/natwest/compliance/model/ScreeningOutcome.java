package com.natwest.compliance.model;

import java.util.Objects;

/**
 * The result of screening one transfer.
 *
 * <p>A rejection always carries a machine-readable reason. "Computer says no" is unusable by the
 * caller and unexplainable to a customer, so the reason is part of the contract rather than an
 * optional extra.
 *
 * @param decision whether the transfer may proceed
 * @param reason   a stable reason code when rejected; null when approved
 */
public record ScreeningOutcome(Decision decision, String reason) {

    public ScreeningOutcome {
        Objects.requireNonNull(decision, "decision");
        if (decision == Decision.REJECTED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A rejection must state a reason");
        }
    }

    public static ScreeningOutcome approved() {
        return new ScreeningOutcome(Decision.APPROVED, null);
    }

    public static ScreeningOutcome rejected(String reason) {
        return new ScreeningOutcome(Decision.REJECTED, reason);
    }

    public boolean isApproved() {
        return decision == Decision.APPROVED;
    }
}

