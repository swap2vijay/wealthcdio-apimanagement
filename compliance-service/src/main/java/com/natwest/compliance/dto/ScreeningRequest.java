package com.natwest.compliance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A request to screen one proposed transfer.
 *
 * <p>Carries the caller's {@code reference} so that this service's logs and the ledger service's can
 * be joined for a single payment. Without it, investigating "why was this stopped?" means correlating
 * two systems by timestamp and amount, which stops working the moment two similar payments occur in
 * the same second.
 *
 * @param reference            the ledger service's transaction reference
 * @param sourceAccountId      who is paying
 * @param destinationAccountId who is being paid
 * @param amount               how much is moving
 * @param currency             ISO code, for completeness of the audit record
 */
public record ScreeningRequest(

        @NotBlank(message = "reference is required")
        @Size(max = 64, message = "reference must not exceed 64 characters")
        String reference,

        @NotBlank(message = "sourceAccountId is required")
        @Size(max = 36, message = "sourceAccountId must not exceed 36 characters")
        String sourceAccountId,

        @NotBlank(message = "destinationAccountId is required")
        @Size(max = 36, message = "destinationAccountId must not exceed 36 characters")
        String destinationAccountId,

        @NotNull(message = "amount is required")
        BigDecimal amount,

        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
        String currency) {
}

