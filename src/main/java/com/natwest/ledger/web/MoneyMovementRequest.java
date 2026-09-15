package com.natwest.ledger.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A request to pay money into, or take money out of, a single account.
 *
 * <p>One type serves both deposits and withdrawals: the direction is carried by the endpoint, not
 * by the payload. A shared request with a {@code type} field would let a caller post a withdrawal
 * to the deposit endpoint and force the controller to disambiguate.
 *
 * <p>{@code amount} is required but not otherwise constrained here. Whether it is positive, and
 * whether the account can afford it, are business questions answered by the domain - see
 * {@link OpenAccountRequest} for why that rule is not duplicated into an annotation.
 *
 * @param amount    how much to move
 * @param currency  optional ISO code; omitted means the configured base currency
 * @param narrative optional description that appears on the statement
 */
public record MoneyMovementRequest(

        @NotNull(message = "amount is required")
        BigDecimal amount,

        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO code")
        String currency,

        @Size(max = 140, message = "narrative must not exceed 140 characters")
        String narrative) {
}
