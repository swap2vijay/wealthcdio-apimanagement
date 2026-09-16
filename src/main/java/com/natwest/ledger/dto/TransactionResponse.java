package com.natwest.ledger.dto;

import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.model.Direction;
import com.natwest.ledger.model.LedgerEntry;
import com.natwest.ledger.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One line of a statement.
 *
 * <p>Reports {@code direction} alongside {@code type} even though it is derivable from it. A client
 * rendering a statement needs to know which way the money went in order to colour or sign the row,
 * and requiring every client to hard-code its own mapping from five transaction types to a direction
 * guarantees that one of them eventually gets it wrong.
 *
 * <p>{@code balanceAfter} is the running balance at that point, which is what makes each row
 * independently meaningful on a statement.
 *
 * @param reference shared by every entry of one logical transaction, so the two legs of a transfer
 *                  can be correlated across accounts
 */
public record TransactionResponse(
        UUID entryId,
        String reference,
        String accountId,
        TransactionType type,
        Direction direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String currency,
        Instant occurredAt,
        String narrative) {

    public static TransactionResponse from(LedgerEntry entry) {
        return new TransactionResponse(
                entry.entryId(),
                entry.reference().value(),
                entry.accountId().value(),
                entry.type(),
                entry.direction(),
                entry.amount().amount(),
                entry.balanceAfter().amount(),
                entry.amount().currency().getCurrencyCode(),
                entry.occurredAt(),
                entry.narrative());
    }

    public static List<TransactionResponse> from(List<LedgerEntry> entries) {
        return entries.stream().map(TransactionResponse::from).toList();
    }
}

