package com.natwest.ledger.model;

import com.natwest.ledger.exception.CurrencyMismatchException;
import com.natwest.ledger.exception.ErrorCode;
import com.natwest.ledger.exception.InsufficientFundsException;
import com.natwest.ledger.exception.InvalidAmountException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Specifies the behaviour of an {@link Account}: what it allows, what it refuses, and what it
 * records. These are the acceptance criteria for accounts, overdraft prevention and the ledger,
 * expressed as executable statements.
 */
@DisplayName("An account")
class AccountTest {

    private static final AccountId ACC_1 = AccountId.of("ACC-1001");
    private static final AccountId ACC_2 = AccountId.of("ACC-2002");
    private static final Currency USD = Currency.getInstance("USD");

    /** A fixed instant: the domain never reads the clock, so tests never have to stub one. */
    private static final Instant AT = Instant.parse("2026-04-20T10:15:30Z");

    private static final TransactionReference REF = TransactionReference.of("REF-1");

    private static Account accountWith(String openingBalance) {
        return Account.open(ACC_1, "Ada Lovelace", Money.gbp(openingBalance));
    }

    @Nested
    @DisplayName("when opened")
    class Opening {

        @Test
        @DisplayName("starts with the opening balance it was given")
        void startsWithOpeningBalance() {
            assertThat(accountWith("250.00").balance()).isEqualTo(Money.gbp("250.00"));
        }

        @Test
        @DisplayName("may be opened empty")
        void mayBeOpenedEmpty() {
            assertThat(accountWith("0").balance()).isEqualTo(Money.zero(Money.GBP));
        }

        @Test
        @DisplayName("refuses to be opened already overdrawn")
        void refusesNegativeOpeningBalance() {
            assertThatThrownBy(() -> Account.open(ACC_1, "Ada Lovelace", Money.gbp("-0.01")))
                    .isInstanceOf(InvalidAmountException.class)
                    .hasMessageContaining("Opening balance cannot be negative");
        }

        @Test
        @DisplayName("requires a holder name")
        void requiresHolderName() {
            assertThatThrownBy(() -> Account.open(ACC_1, "  ", Money.gbp("10.00")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("holder name is required");
        }

        @Test
        @DisplayName("is identified by its id, not by its balance")
        void isIdentifiedById() {
            Account poorer = accountWith("1.00");
            Account richer = accountWith("999.00");

            assertThat(poorer).isEqualTo(richer);
            assertThat(poorer).hasSameHashCodeAs(richer);
        }

        @Test
        @DisplayName("is a different account when the id differs")
        void differsWhenIdDiffers() {
            assertThat(accountWith("10.00"))
                    .isNotEqualTo(Account.open(ACC_2, "Ada Lovelace", Money.gbp("10.00")));
        }
    }

    @Nested
    @DisplayName("when money is deposited")
    class Depositing {

        @Test
        @DisplayName("increases the balance by the amount paid in")
        void increasesBalance() {
            Account account = accountWith("100.00");

            account.deposit(Money.gbp("25.50"), REF, AT, "Salary");

            assertThat(account.balance()).isEqualTo(Money.gbp("125.50"));
        }

        @Test
        @DisplayName("records a ledger entry describing exactly what happened")
        void recordsLedgerEntry() {
            Account account = accountWith("100.00");

            LedgerEntry entry = account.deposit(Money.gbp("25.50"), REF, AT, "Salary");

            assertThat(entry.accountId()).isEqualTo(ACC_1);
            assertThat(entry.type()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(entry.direction()).isEqualTo(Direction.CREDIT);
            assertThat(entry.amount()).isEqualTo(Money.gbp("25.50"));
            assertThat(entry.balanceAfter()).isEqualTo(Money.gbp("125.50"));
            assertThat(entry.occurredAt()).isEqualTo(AT);
            assertThat(entry.reference()).isEqualTo(REF);
            assertThat(entry.narrative()).isEqualTo("Salary");
            assertThat(entry.entryId()).isNotNull();
        }

        @Test
        @DisplayName("gives every entry its own identity")
        void givesEveryEntryItsOwnIdentity() {
            Account account = accountWith("100.00");

            LedgerEntry first = account.deposit(Money.gbp("1.00"), REF, AT, null);
            LedgerEntry second = account.deposit(Money.gbp("1.00"), REF, AT, null);

            assertThat(first.entryId()).isNotEqualTo(second.entryId());
        }

        @Test
        @DisplayName("refuses a zero deposit, which would record a movement that never happened")
        void refusesZeroDeposit() {
            Account account = accountWith("100.00");

            assertThatThrownBy(() -> account.deposit(Money.zero(Money.GBP), REF, AT, null))
                    .isInstanceOf(InvalidAmountException.class);

            assertThat(account.balance()).isEqualTo(Money.gbp("100.00"));
        }

        @Test
        @DisplayName("refuses a negative deposit, which would be a withdrawal in disguise")
        void refusesNegativeDeposit() {
            Account account = accountWith("100.00");

            assertThatThrownBy(() -> account.deposit(Money.gbp("-10.00"), REF, AT, null))
                    .isInstanceOf(InvalidAmountException.class);

            assertThat(account.balance()).isEqualTo(Money.gbp("100.00"));
        }

        @Test
        @DisplayName("refuses a deposit in a currency the account does not hold")
        void refusesForeignCurrency() {
            Account account = accountWith("100.00");

            assertThatThrownBy(() -> account.deposit(Money.of("10.00", USD), REF, AT, null))
                    .isInstanceOf(CurrencyMismatchException.class);

            assertThat(account.balance()).isEqualTo(Money.gbp("100.00"));
        }
    }

    @Nested
    @DisplayName("when money is withdrawn")
    class Withdrawing {

        @Test
        @DisplayName("decreases the balance by the amount taken out")
        void decreasesBalance() {
            Account account = accountWith("100.00");

            account.withdraw(Money.gbp("40.00"), REF, AT, "Cash machine");

            assertThat(account.balance()).isEqualTo(Money.gbp("60.00"));
        }

        @Test
        @DisplayName("records a debit entry with the resulting balance")
        void recordsDebitEntry() {
            Account account = accountWith("100.00");

            LedgerEntry entry = account.withdraw(Money.gbp("40.00"), REF, AT, "Cash machine");

            assertThat(entry.type()).isEqualTo(TransactionType.WITHDRAWAL);
            assertThat(entry.direction()).isEqualTo(Direction.DEBIT);
            assertThat(entry.amount()).isEqualTo(Money.gbp("40.00"));
            assertThat(entry.balanceAfter()).isEqualTo(Money.gbp("60.00"));
        }

        @Test
        @DisplayName("allows the balance to be emptied exactly")
        void allowsExactEmptying() {
            Account account = accountWith("100.00");

            account.withdraw(Money.gbp("100.00"), REF, AT, null);

            assertThat(account.balance()).isEqualTo(Money.zero(Money.GBP));
        }

        @Test
        @DisplayName("refuses to overdraw by even one penny")
        void refusesOverdraftByOnePenny() {
            Account account = accountWith("100.00");

            assertThatThrownBy(() -> account.withdraw(Money.gbp("100.01"), REF, AT, null))
                    .isInstanceOf(InsufficientFundsException.class);
        }

        @Test
        @DisplayName("leaves the balance untouched when a withdrawal is refused")
        void leavesBalanceUntouchedWhenRefused() {
            Account account = accountWith("100.00");

            assertThatThrownBy(() -> account.withdraw(Money.gbp("500.00"), REF, AT, null))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(account.balance())
                    .as("a rejected withdrawal must not partially apply")
                    .isEqualTo(Money.gbp("100.00"));
        }

        @Test
        @DisplayName("refuses to withdraw from an empty account")
        void refusesToWithdrawFromEmptyAccount() {
            Account account = accountWith("0");

            assertThatThrownBy(() -> account.withdraw(Money.gbp("0.01"), REF, AT, null))
                    .isInstanceOf(InsufficientFundsException.class);
        }

        @Test
        @DisplayName("reports the shortfall as structured data, not just prose")
        void reportsShortfall() {
            Account account = accountWith("100.00");

            InsufficientFundsException thrown = assertThrows(InsufficientFundsException.class,
                    () -> account.withdraw(Money.gbp("130.00"), REF, AT, null));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
            assertThat(thrown.details())
                    .containsEntry("accountId", "ACC-1001")
                    .containsEntry("balance", "100.00")
                    .containsEntry("requested", "130.00")
                    .containsEntry("shortfall", "30.00")
                    .containsEntry("currency", "GBP");
        }

        @Test
        @DisplayName("refuses a zero withdrawal")
        void refusesZeroWithdrawal() {
            Account account = accountWith("100.00");

            assertThatThrownBy(() -> account.withdraw(Money.zero(Money.GBP), REF, AT, null))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        @DisplayName("refuses a negative withdrawal, which would quietly credit the account")
        void refusesNegativeWithdrawal() {
            Account account = accountWith("100.00");

            assertThatThrownBy(() -> account.withdraw(Money.gbp("-50.00"), REF, AT, null))
                    .isInstanceOf(InvalidAmountException.class);

            assertThat(account.balance()).isEqualTo(Money.gbp("100.00"));
        }

        @Test
        @DisplayName("reports a malformed amount as such, rather than blaming the balance")
        void prefersAmountComplaintOverAffordabilityComplaint() {
            Account account = accountWith("0");

            // Both rules are violated: the amount is negative AND the account is empty.
            // The amount is the caller's actual mistake, so that is what is reported.
            assertThatThrownBy(() -> account.withdraw(Money.gbp("-1.00"), REF, AT, null))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("when it takes part in a transfer")
    class TransferLegs {

        @Test
        @DisplayName("debits the source and records it as an outbound transfer")
        void debitsSource() {
            Account source = accountWith("100.00");

            LedgerEntry entry = source.transferOut(Money.gbp("30.00"), REF, AT, "To ACC-2002");

            assertThat(source.balance()).isEqualTo(Money.gbp("70.00"));
            assertThat(entry.type()).isEqualTo(TransactionType.TRANSFER_OUT);
            assertThat(entry.direction()).isEqualTo(Direction.DEBIT);
        }

        @Test
        @DisplayName("credits the destination and records it as an inbound transfer")
        void creditsDestination() {
            Account destination = Account.open(ACC_2, "Grace Hopper", Money.gbp("10.00"));

            LedgerEntry entry = destination.transferIn(Money.gbp("30.00"), REF, AT, "From ACC-1001");

            assertThat(destination.balance()).isEqualTo(Money.gbp("40.00"));
            assertThat(entry.type()).isEqualTo(TransactionType.TRANSFER_IN);
            assertThat(entry.direction()).isEqualTo(Direction.CREDIT);
        }

        @Test
        @DisplayName("applies the same overdraft guard to transfers as to withdrawals")
        void appliesOverdraftGuardToTransfers() {
            Account source = accountWith("20.00");

            assertThatThrownBy(() -> source.transferOut(Money.gbp("20.01"), REF, AT, null))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(source.balance()).isEqualTo(Money.gbp("20.00"));
        }

        @Test
        @DisplayName("ties both legs together through a shared reference")
        void tiesLegsTogetherByReference() {
            Account source = accountWith("100.00");
            Account destination = Account.open(ACC_2, "Grace Hopper", Money.gbp("0"));

            LedgerEntry debit = source.transferOut(Money.gbp("30.00"), REF, AT, null);
            LedgerEntry credit = destination.transferIn(Money.gbp("30.00"), REF, AT, null);

            assertThat(debit.reference()).isEqualTo(credit.reference());
        }

        @Test
        @DisplayName("puts the money back as a fresh credit when a transfer is reversed")
        void reversesByAppendingCredit() {
            Account source = accountWith("100.00");
            source.transferOut(Money.gbp("30.00"), REF, AT, null);

            LedgerEntry reversal = source.reverseTransfer(Money.gbp("30.00"), REF, AT, "Reversed");

            assertThat(source.balance())
                    .as("a reversal must restore the balance exactly")
                    .isEqualTo(Money.gbp("100.00"));
            assertThat(reversal.type()).isEqualTo(TransactionType.TRANSFER_REVERSAL);
            assertThat(reversal.direction()).isEqualTo(Direction.CREDIT);
        }
    }

    @Nested
    @DisplayName("when its ledger is reconciled")
    class Reconciliation {

        @Test
        @DisplayName("replaying every entry reproduces the current balance")
        void replayingEntriesReproducesBalance() {
            Account account = accountWith("0");

            List<LedgerEntry> ledger = List.of(
                    account.deposit(Money.gbp("500.00"), REF, AT, null),
                    account.withdraw(Money.gbp("120.50"), REF, AT, null),
                    account.transferOut(Money.gbp("80.00"), REF, AT, null),
                    account.transferIn(Money.gbp("15.25"), REF, AT, null),
                    account.reverseTransfer(Money.gbp("80.00"), REF, AT, null));

            Money replayed = ledger.stream()
                    .map(LedgerEntry::signedAmount)
                    .reduce(Money.zero(Money.GBP), Money::plus);

            assertThat(replayed).isEqualTo(account.balance());
            assertThat(account.balance()).isEqualTo(Money.gbp("394.75"));
        }

        @Test
        @DisplayName("stamps each entry with the running balance at that point")
        void stampsRunningBalance() {
            Account account = accountWith("0");

            LedgerEntry first = account.deposit(Money.gbp("100.00"), REF, AT, null);
            LedgerEntry second = account.withdraw(Money.gbp("30.00"), REF, AT, null);

            assertThat(first.balanceAfter()).isEqualTo(Money.gbp("100.00"));
            assertThat(second.balanceAfter()).isEqualTo(Money.gbp("70.00"));
        }
    }
}

