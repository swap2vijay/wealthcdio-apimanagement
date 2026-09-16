package com.natwest.ledger.model;

import com.natwest.ledger.exception.CurrencyMismatchException;
import com.natwest.ledger.exception.InvalidAmountException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Specifies the contract of {@link Money}: what counts as a valid amount, how amounts combine,
 * and where the type refuses to guess.
 */
@DisplayName("Money")
class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Nested
    @DisplayName("when created")
    class Creation {

        @Test
        @DisplayName("widens a whole number to minor-unit precision")
        void widensWholeNumbersToTwoDecimalPlaces() {
            assertThat(Money.gbp("10").toPlainString()).isEqualTo("10.00");
        }

        @Test
        @DisplayName("keeps an amount already at minor-unit precision unchanged")
        void keepsTwoDecimalPlaces() {
            assertThat(Money.gbp("10.45").toPlainString()).isEqualTo("10.45");
        }

        @Test
        @DisplayName("accepts a single decimal place and widens it")
        void widensSingleDecimalPlace() {
            assertThat(Money.gbp("10.4").toPlainString()).isEqualTo("10.40");
        }

        @Test
        @DisplayName("understands scientific notation, since BigDecimal permits it")
        void acceptsScientificNotation() {
            assertThat(Money.gbp("1E+2").toPlainString()).isEqualTo("100.00");
        }

        @Test
        @DisplayName("rejects sub-penny precision rather than silently rounding it away")
        void rejectsMoreThanTwoDecimalPlaces() {
            assertThatThrownBy(() -> Money.gbp("10.005"))
                    .isInstanceOf(InvalidAmountException.class)
                    .hasMessageContaining("more than 2 decimal places");
        }

        @ParameterizedTest(name = "rejects \"{0}\"")
        @DisplayName("rejects text that is not a decimal number")
        @ValueSource(strings = {"abc", "10.00.00", "1,000.00", "Â£10", "10 GBP", "--1", ""})
        void rejectsNonNumericText(String candidate) {
            assertThatThrownBy(() -> Money.gbp(candidate))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        @DisplayName("rejects a null amount")
        void rejectsNullAmount() {
            assertThatThrownBy(() -> Money.of((BigDecimal) null, Money.GBP))
                    .isInstanceOf(InvalidAmountException.class)
                    .hasMessageContaining("null");
        }

        @Test
        @DisplayName("permits a negative value, because arithmetic needs to express one")
        void permitsNegativeValues() {
            assertThat(Money.gbp("-5.00").isNegative()).isTrue();
        }

        @Test
        @DisplayName("zero is zero, and is neither positive nor negative")
        void zeroIsNeitherPositiveNorNegative() {
            Money zero = Money.zero(Money.GBP);

            assertThat(zero.isZero()).isTrue();
            assertThat(zero.isPositive()).isFalse();
            assertThat(zero.isNegative()).isFalse();
        }
    }

    @Nested
    @DisplayName("when combined")
    class Arithmetic {

        @Test
        @DisplayName("adds amounts of the same currency")
        void adds() {
            assertThat(Money.gbp("10.50").plus(Money.gbp("0.75"))).isEqualTo(Money.gbp("11.25"));
        }

        @Test
        @DisplayName("subtracts amounts of the same currency")
        void subtracts() {
            assertThat(Money.gbp("10.50").minus(Money.gbp("0.75"))).isEqualTo(Money.gbp("9.75"));
        }

        @Test
        @DisplayName("goes negative when subtracting more than it holds")
        void subtractsBelowZero() {
            assertThat(Money.gbp("1.00").minus(Money.gbp("2.50"))).isEqualTo(Money.gbp("-1.50"));
        }

        @Test
        @DisplayName("adds decimal fractions exactly, where floating point would drift")
        void addsDecimalFractionsExactly() {
            Money sum = Money.gbp("0.10").plus(Money.gbp("0.20"));

            // The canonical floating-point failure: 0.1 + 0.2 == 0.30000000000000004
            assertThat(sum).isEqualTo(Money.gbp("0.30"));
            assertThat(sum.toPlainString()).isEqualTo("0.30");
        }

        @Test
        @DisplayName("survives repeated addition without drift")
        void survivesRepeatedAddition() {
            Money running = Money.zero(Money.GBP);
            for (int i = 0; i < 1_000; i++) {
                running = running.plus(Money.gbp("0.01"));
            }

            assertThat(running).isEqualTo(Money.gbp("10.00"));
        }

        @Test
        @DisplayName("refuses to add a different currency instead of inventing a rate")
        void refusesToAddDifferentCurrencies() {
            assertThatThrownBy(() -> Money.gbp("10.00").plus(Money.of("10.00", USD)))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("does not convert between currencies");
        }

        @Test
        @DisplayName("leaves the original untouched, being immutable")
        void isImmutable() {
            Money original = Money.gbp("10.00");

            original.plus(Money.gbp("5.00"));

            assertThat(original).isEqualTo(Money.gbp("10.00"));
        }
    }

    @Nested
    @DisplayName("when compared")
    class Comparison {

        @Test
        @DisplayName("covers an equal amount")
        void isAtLeastEqualAmount() {
            assertThat(Money.gbp("10.00").isAtLeast(Money.gbp("10.00"))).isTrue();
        }

        @Test
        @DisplayName("does not cover an amount one penny larger")
        void doesNotCoverLargerAmount() {
            assertThat(Money.gbp("10.00").isAtLeast(Money.gbp("10.01"))).isFalse();
        }

        @Test
        @DisplayName("orders by amount within a currency")
        void ordersByAmount() {
            assertThat(Money.gbp("9.99")).isLessThan(Money.gbp("10.00"));
        }

        @Test
        @DisplayName("refuses to order across currencies, having no basis to do so")
        void refusesToCompareAcrossCurrencies() {
            assertThatThrownBy(() -> Money.gbp("10.00").compareTo(Money.of("10.00", USD)))
                    .isInstanceOf(CurrencyMismatchException.class);
        }
    }

    @Nested
    @DisplayName("when tested for equality")
    class Equality {

        @Test
        @DisplayName("treats differently written but equal amounts as the same value")
        void normalisesScaleBeforeComparing() {
            assertThat(Money.gbp("10")).isEqualTo(Money.gbp("10.00"));
            assertThat(Money.gbp("10")).hasSameHashCodeAs(Money.gbp("10.00"));
        }

        @Test
        @DisplayName("treats the same number in different currencies as different values")
        void currencyIsPartOfIdentity() {
            assertThat(Money.gbp("10.00")).isNotEqualTo(Money.of("10.00", USD));
        }
    }

    @Nested
    @DisplayName("when guarding an operation")
    class PositiveGuard {

        @Test
        @DisplayName("allows a positive movement")
        void allowsPositive() {
            assertThatNoException().isThrownBy(() -> Money.gbp("0.01").requirePositiveFor("A deposit"));
        }

        @Test
        @DisplayName("rejects a zero movement, which would record an event that never happened")
        void rejectsZero() {
            assertThatThrownBy(() -> Money.zero(Money.GBP).requirePositiveFor("A deposit"))
                    .isInstanceOf(InvalidAmountException.class)
                    .hasMessageContaining("requires a positive amount");
        }

        @Test
        @DisplayName("rejects a negative movement, which would be the opposite operation in disguise")
        void rejectsNegative() {
            assertThatThrownBy(() -> Money.gbp("-1.00").requirePositiveFor("A deposit"))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Test
    @DisplayName("renders amount and currency together for logs and messages")
    void rendersReadably() {
        assertThat(Money.gbp("1234.50")).hasToString("1234.50 GBP");
    }
}

