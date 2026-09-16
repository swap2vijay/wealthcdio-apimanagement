package com.natwest.ledger.dto;

import com.natwest.ledger.service.TransferReceipt;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The outcome of a transfer.
 *
 * <p>Returns both legs rather than a bare acknowledgement. The payer's new balance is the first
 * thing they will want, and including the credit leg lets a caller prove the two sides agree
 * without a follow-up request to each account.
 */
public record TransferResponse(
        String reference,
        String sourceAccountId,
        String destinationAccountId,
        BigDecimal amount,
        String currency,
        Instant occurredAt,
        TransactionResponse debit,
        TransactionResponse credit) {

    public static TransferResponse from(TransferReceipt receipt) {
        return new TransferResponse(
                receipt.reference().value(),
                receipt.sourceAccountId().value(),
                receipt.destinationAccountId().value(),
                receipt.amount().amount(),
                receipt.amount().currency().getCurrencyCode(),
                receipt.occurredAt(),
                TransactionResponse.from(receipt.debit()),
                TransactionResponse.from(receipt.credit()));
    }
}

