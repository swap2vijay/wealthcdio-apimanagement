package com.natwest.ledger.dto;

import com.natwest.ledger.model.AccountId;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A request to open an account.
 *
 * <p><b>No {@code @DecimalMin} on the opening balance.</b> "Money cannot be negative" is a business
 * rule, and it already lives in the domain where every code path is subject to it. Repeating it as
 * an annotation would create a second copy that can drift from the first, and would mean the domain
 * rule is never exercised through the API. Annotations here therefore describe the <em>shape</em> of
 * a request; the domain judges its <em>meaning</em>.
 *
 * @param accountId      the identifier to assign; normalised to upper case
 * @param holderName     who the account belongs to
 * @param openingBalance optional. Omitted means open the account empty.
 * @param currency       optional ISO code. Omitted means the service's configured base currency.
 */
public record OpenAccountRequest(

        @NotBlank(message = "accountId is required")
        @Size(max = 36, message = "accountId must not exceed 36 characters")
        String accountId,

        @NotBlank(message = "holderName is required")
        @Size(max = 140, message = "holderName must not exceed 140 characters")
        String holderName,

        BigDecimal openingBalance,

        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
        String currency) {
}

