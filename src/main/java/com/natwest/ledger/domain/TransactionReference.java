package com.natwest.ledger.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Correlates every ledger entry belonging to one logical transaction.
 *
 * <p>A deposit produces a single entry under its own reference. A transfer produces a debit, a
 * credit, and possibly a reversal, all sharing one reference - which is what makes it possible to
 * answer "what happened to this payment?" by looking up a single value across both accounts.
 *
 * <p>In Phase 6 this doubles as the saga's correlation id, and it is what a caller-supplied
 * idempotency key would key on.
 */
public record TransactionReference(String value) {

    private static final int MAX_LENGTH = 64;

    public TransactionReference {
        Objects.requireNonNull(value, "transaction reference");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("A transaction reference must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "A transaction reference must not exceed %d characters".formatted(MAX_LENGTH));
        }
    }

    public static TransactionReference of(String value) {
        return new TransactionReference(value);
    }

    public static TransactionReference newReference() {
        return new TransactionReference(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
