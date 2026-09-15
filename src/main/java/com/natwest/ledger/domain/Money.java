package com.natwest.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable amount of money in a single currency.
 *
 * <p>Three deliberate choices underpin this type.
 *
 * <p><b>{@link BigDecimal}, never {@code double}.</b> Binary floating point cannot represent
 * 0.10 exactly, so {@code 0.1 + 0.2 != 0.3} and repeated interest or fee arithmetic drifts.
 * Money is a decimal quantity and is stored as one.
 *
 * <p><b>Excess precision is rejected, not rounded.</b> Asking to withdraw {@code 10.005} is
 * ambiguous: rounding it to {@code 10.00} or {@code 10.01} both silently alter the customer's
 * instruction. The type refuses the input and makes the caller state what it means. Amounts with
 * <em>less</em> precision are widened exactly, so {@code 10} becomes {@code 10.00}.
 *
 * <p><b>Currency is part of the value.</b> {@code 10 GBP} and {@code 10 USD} are neither equal
 * nor addable. Arithmetic across currencies throws rather than producing a meaningless number;
 * this service performs no FX conversion.
 *
 * <p>Signed values are permitted so that intermediate arithmetic (a shortfall, a reversal) can be
 * expressed. Enforcing "a deposit must be positive" is the job of the operation being requested,
 * not of the quantity itself, so that rule lives in {@link Account}.
 */
public final class Money implements Comparable<Money> {

    /** Minor-unit precision. Fixed at 2 because every currency this service handles uses 2. */
    public static final int SCALE = 2;

    public static final Currency GBP = Currency.getInstance("GBP");

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(currency, "currency");
        if (amount == null) {
            throw new InvalidAmountException("An amount is required but was null");
        }
        if (amount.scale() > SCALE) {
            throw new InvalidAmountException(
                    "Amount %s has more than %d decimal places, which %s cannot represent"
                            .formatted(amount.toPlainString(), SCALE, currency.getCurrencyCode()),
                    Map.of("amount", amount.toPlainString(),
                            "currency", currency.getCurrencyCode(),
                            "maximumDecimalPlaces", SCALE));
        }
        // Exact: scale is known to be <= SCALE, so no rounding can occur.
        return new Money(amount.setScale(SCALE, RoundingMode.UNNECESSARY), currency);
    }

    /**
     * Parses a decimal literal, e.g. {@code Money.of("10.50", GBP)}.
     *
     * @throws InvalidAmountException if the text is not a valid decimal number
     */
    public static Money of(String amount, Currency currency) {
        if (amount == null || amount.isBlank()) {
            throw new InvalidAmountException("An amount is required but was blank");
        }
        try {
            return of(new BigDecimal(amount.trim()), currency);
        } catch (NumberFormatException e) {
            throw new InvalidAmountException(
                    "Amount '%s' is not a valid decimal number".formatted(amount),
                    Map.of("amount", amount));
        }
    }

    /** Convenience for the service's default currency, which keeps tests readable. */
    public static Money gbp(String amount) {
        return of(amount, GBP);
    }

    public static Money zero(Currency currency) {
        return of(BigDecimal.ZERO, currency);
    }

    public BigDecimal amount() {
        return amount;
    }

    public Currency currency() {
        return currency;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    /** True when this amount can cover {@code other}, i.e. {@code this >= other}. */
    public boolean isAtLeast(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) >= 0;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount) < 0;
    }

    /**
     * Guards an operation that only makes sense for a strictly positive movement.
     *
     * <p>Zero is rejected alongside negatives: a zero-value deposit moves no money but would
     * still append a ledger entry, leaving a statement full of events that never happened.
     * Negative amounts are rejected because a negative deposit is a withdrawal wearing a
     * disguise, and would bypass the overdraft guard entirely.
     */
    Money requirePositiveFor(String operation) {
        if (!isPositive()) {
            throw InvalidAmountException.notPositive(operation, this);
        }
        return this;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    /** The bare numeric value, e.g. {@code "10.00"}, for wire formats and structured logs. */
    public String toPlainString() {
        return amount.toPlainString();
    }

    /**
     * Orders by amount. Only meaningful within one currency, so comparing across currencies
     * throws rather than imposing an arbitrary ordering.
     */
    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    /**
     * Value equality on both amount and currency.
     *
     * <p>Safe to use {@code BigDecimal.equals} here (which is scale-sensitive) only because the
     * factory normalises every instance to {@link #SCALE}, so {@code 10} and {@code 10.00}
     * arrive as the same representation.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return amount.equals(other.amount) && currency.equals(other.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
