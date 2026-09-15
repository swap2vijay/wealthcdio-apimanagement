package com.natwest.ledger.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A request to move money between two accounts.
 *
 * <p>Both accounts are named in the body rather than one being taken from the path, because a
 * transfer is not a sub-resource of either account - it acts on both, and putting one in the URL
 * would imply an ownership that does not exist.
 *
 * <p>That source and destination must differ is enforced by the domain, not by an annotation: it is
 * a rule about transfers, and it belongs with the other transfer rules.
 *
 * @param sourceAccountId      the account to debit
 * @param destinationAccountId the account to credit
 * @param amount               how much to move
 * @param currency             optional ISO code; omitted means the configured base currency
 * @param narrative            optional description applied to both legs
 */
public record TransferRequest(

        @NotBlank(message = "sourceAccountId is required")
        @Size(max = 36, message = "sourceAccountId must not exceed 36 characters")
        String sourceAccountId,

        @NotBlank(message = "destinationAccountId is required")
        @Size(max = 36, message = "destinationAccountId must not exceed 36 characters")
        String destinationAccountId,

        @NotNull(message = "amount is required")
        BigDecimal amount,

        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
        String currency,

        @Size(max = 140, message = "narrative must not exceed 140 characters")
        String narrative) {
}
