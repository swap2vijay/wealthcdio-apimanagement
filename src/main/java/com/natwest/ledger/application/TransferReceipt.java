package com.natwest.ledger.application;

import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.LedgerEntry;
import com.natwest.ledger.domain.Money;
import com.natwest.ledger.domain.TransactionReference;
import com.natwest.ledger.domain.TransactionType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The outcome of a completed transfer: both legs, and the reference that ties them together.
 *
 * <p>Returns both entries rather than just a success flag so the caller can show the payer their
 * new balance and prove, from the record itself, that the two legs agree. The constructor asserts
 * that agreement, which turns "the debit and credit must match" from a hope into a checked
 * invariant at the boundary where a transfer is reported.
 *
 * @param reference shared by both legs
 * @param debit     the {@code TRANSFER_OUT} written against the source account
 * @param credit    the {@code TRANSFER_IN} written against the destination account
 */
public record TransferReceipt(TransactionReference reference, LedgerEntry debit, LedgerEntry credit) {

    public TransferReceipt {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(debit, "debit");
        Objects.requireNonNull(credit, "credit");

        if (debit.type() != TransactionType.TRANSFER_OUT) {
            throw new IllegalArgumentException("The debit leg must be a TRANSFER_OUT but was " + debit.type());
        }
        if (credit.type() != TransactionType.TRANSFER_IN) {
            throw new IllegalArgumentException("The credit leg must be a TRANSFER_IN but was " + credit.type());
        }
        if (!debit.amount().equals(credit.amount())) {
            throw new IllegalArgumentException(
                    "A transfer must move the same amount on both legs but debited %s and credited %s"
                            .formatted(debit.amount(), credit.amount()));
        }
        if (!debit.reference().equals(reference) || !credit.reference().equals(reference)) {
            throw new IllegalArgumentException("Both legs of a transfer must carry the transfer's reference");
        }
        if (debit.accountId().equals(credit.accountId())) {
            throw new IllegalArgumentException("A transfer cannot have the same account on both legs");
        }
    }

    public AccountId sourceAccountId() {
        return debit.accountId();
    }

    public AccountId destinationAccountId() {
        return credit.accountId();
    }

    /** The amount moved. Identical on both legs, as the constructor guarantees. */
    public Money amount() {
        return debit.amount();
    }

    public Instant occurredAt() {
        return debit.occurredAt();
    }

    /** Both legs, source first, for callers that want to record or display the pair. */
    public List<LedgerEntry> entries() {
        return List.of(debit, credit);
    }
}
