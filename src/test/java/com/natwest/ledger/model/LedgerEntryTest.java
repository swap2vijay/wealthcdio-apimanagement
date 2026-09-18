package com.natwest.ledger.model;

import com.natwest.ledger.exception.InvalidAmountException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Specifies the invariants a single ledger line must satisfy to be trustworthy. */
@DisplayName("A ledger entry")
class LedgerEntryTest {

    private static final Instant AT = Instant.parse("2026-04-20T10:15:30Z");
    private static final AccountId ACCOUNT = AccountId.of("ACC-1001");
    private static final TransactionReference REF = TransactionReference.of("REF-1");

    private static LedgerEntry entry(TransactionType type, String amount) {
        return new LedgerEntry(UUID.randomUUID(), REF, ACCOUNT, type,
                Money.gbp(amount), Money.gbp("500.00"), AT, "narrative");
    }

    @Test
    @DisplayName("derives its direction from the kind of transaction it records")
    void derivesDirectionFromType() {
        assertThat(entry(TransactionType.DEPOSIT, "10.00").direction()).isEqualTo(Direction.CREDIT);
        assertThat(entry(TransactionType.TRANSFER_IN, "10.00").direction()).isEqualTo(Direction.CREDIT);
        assertThat(entry(TransactionType.WITHDRAWAL, "10.00").direction()).isEqualTo(Direction.DEBIT);
        assertThat(entry(TransactionType.TRANSFER_OUT, "10.00").direction()).isEqualTo(Direction.DEBIT);
    }

    @Test
    @DisplayName("reports a credit as a positive effect on the balance")
    void reportsCreditAsPositive() {
        assertThat(entry(TransactionType.DEPOSIT, "25.00").signedAmount()).isEqualTo(Money.gbp("25.00"));
    }

    @Test
    @DisplayName("reports a debit as a negative effect on the balance")
    void reportsDebitAsNegative() {
        assertThat(entry(TransactionType.WITHDRAWAL, "25.00").signedAmount()).isEqualTo(Money.gbp("-25.00"));
    }

    @Test
    @DisplayName("stores the amount as a positive magnitude, never a signed one")
    void refusesNegativeAmount() {
        assertThatThrownBy(() -> entry(TransactionType.WITHDRAWAL, "-25.00"))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    @DisplayName("refuses to record a zero-value movement")
    void refusesZeroAmount() {
        assertThatThrownBy(() -> entry(TransactionType.DEPOSIT, "0"))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    @DisplayName("requires every field that makes it auditable")
    void requiresAuditableFields() {
        assertThatThrownBy(() -> new LedgerEntry(UUID.randomUUID(), REF, ACCOUNT,
                TransactionType.DEPOSIT, Money.gbp("1.00"), Money.gbp("1.00"), null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("occurredAt");

        assertThatThrownBy(() -> new LedgerEntry(UUID.randomUUID(), null, ACCOUNT,
                TransactionType.DEPOSIT, Money.gbp("1.00"), Money.gbp("1.00"), AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reference");
    }

    @Test
    @DisplayName("allows an absent narrative, which is optional context rather than a fact")
    void allowsAbsentNarrative() {
        LedgerEntry withoutNarrative = new LedgerEntry(UUID.randomUUID(), REF, ACCOUNT,
                TransactionType.DEPOSIT, Money.gbp("1.00"), Money.gbp("1.00"), AT, null);

        assertThat(withoutNarrative.narrative()).isNull();
    }
}

