package com.natwest.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable line in an account's ledger: a single movement of money and its effect.
 *
 * <p>Records rather than mutates. A ledger is append-only - correcting a mistake means appending
 * a compensating entry, never editing history - so the entry is a {@code record} with no setters,
 * and that property is enforced by the type rather than by convention.
 *
 * <p><b>Why store {@code balanceAfter}.</b> It is derivable by replaying every prior entry, which
 * is the textbook argument against storing it. It is stored anyway because it is the running
 * balance a customer sees on a statement, it makes each line independently meaningful, and it
 * turns reconciliation into a cheap local check: replaying the ledger must reproduce the recorded
 * balance, so any divergence is detectable rather than silent.
 *
 * @param entryId     unique identity of this line
 * @param reference   correlates the entries belonging to one logical transaction
 * @param accountId   the account whose ledger this line belongs to
 * @param type        the business meaning of the movement
 * @param amount      a strictly positive magnitude; the sign lives in {@link #direction()}
 * @param balanceAfter the account balance once this entry had been applied
 * @param occurredAt  when the movement was applied, always UTC
 * @param narrative   optional human-readable description, e.g. "Transfer to ACC-2"
 */
public record LedgerEntry(
        UUID entryId,
        TransactionReference reference,
        AccountId accountId,
        TransactionType type,
        Money amount,
        Money balanceAfter,
        Instant occurredAt,
        String narrative) {

    public LedgerEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(occurredAt, "occurredAt");

        // An entry recording a zero or negative movement would misrepresent the ledger: the
        // magnitude is always positive and the direction carries the sign.
        if (!amount.isPositive()) {
            throw InvalidAmountException.notPositive("A ledger entry", amount);
        }
    }

    /**
     * Creates an entry, assigning it a fresh identity.
     *
     * <p>Package-private on purpose: only {@link Account} may mint entries for itself, so an
     * entry cannot be fabricated that disagrees with the balance it claims to have produced.
     */
    static LedgerEntry of(TransactionReference reference,
                          AccountId accountId,
                          TransactionType type,
                          Money amount,
                          Money balanceAfter,
                          Instant occurredAt,
                          String narrative) {
        return new LedgerEntry(UUID.randomUUID(), reference, accountId, type,
                amount, balanceAfter, occurredAt, narrative);
    }

    /** Which way the money moved, derived from the transaction type. */
    public Direction direction() {
        return type.direction();
    }

    /**
     * The effect of this entry on the balance, as a signed amount.
     *
     * <p>Useful for reconciliation: summing {@code signedAmount()} across an account's ledger must
     * equal its balance.
     */
    public Money signedAmount() {
        return type.isCredit() ? amount : Money.zero(amount.currency()).minus(amount);
    }
}
