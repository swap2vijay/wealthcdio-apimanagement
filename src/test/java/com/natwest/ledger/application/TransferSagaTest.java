package com.natwest.ledger.application;

import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.InsufficientFundsException;
import com.natwest.ledger.domain.LedgerEntry;
import com.natwest.ledger.domain.Money;
import com.natwest.ledger.domain.SameAccountTransferException;
import com.natwest.ledger.domain.TransactionType;
import com.natwest.ledger.error.ErrorCode;
import com.natwest.ledger.infrastructure.memory.InMemoryAccountRepository;
import com.natwest.ledger.infrastructure.memory.InMemoryLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Specifies the transfer saga: what it completes, what it refuses, and what it puts back.
 *
 * <p>The essential property asserted throughout is that <b>money is never left in limbo</b>. Every
 * failure path checks both balances afterwards, not just the exception, because the entire justification
 * for a saga is that it restores the invariant a single transaction would have preserved for free. A test
 * that only asserted "it threw" would pass just as happily against an implementation that quietly kept
 * the customer's money.
 *
 * <p>Note that {@code @Transactional} on the saga's steps is inert here: these are plain objects, not
 * Spring beans, so nothing is proxied. That is deliberate - it keeps these tests fast and focused on the
 * orchestration logic. The real transaction boundaries are exercised by the Spring-based API tests.
 */
@DisplayName("The transfer saga")
class TransferSagaTest {

    private static final Instant NOW = Instant.parse("2026-04-20T10:15:30Z");
    private static final AccountId ALICE = AccountId.of("ACC-1001");
    private static final AccountId BOB = AccountId.of("ACC-2002");
    private static final AccountId UNKNOWN = AccountId.of("ACC-9999");

    private InMemoryAccountRepository accounts;
    private FailableLedgerRepository ledger;
    private ProgrammableComplianceGateway compliance;
    private AccountService accountService;
    private TransferSaga saga;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        accounts = new InMemoryAccountRepository();
        ledger = new FailableLedgerRepository(new InMemoryLedgerRepository());
        compliance = new ProgrammableComplianceGateway();

        accountService = new AccountService(accounts, ledger, clock);
        saga = new TransferSaga(new TransferSagaSteps(accounts, ledger), compliance, clock);
    }

    private void givenAccount(AccountId id, String balance) {
        accountService.openAccount(id, "Holder of " + id, Money.gbp(balance));
    }

    private Money balanceOf(AccountId id) {
        return accounts.findById(id).orElseThrow().balance();
    }

    private List<LedgerEntry> statementOf(AccountId id) {
        return ledger.findByAccountId(id);
    }

    private List<TransactionType> entryTypesOf(AccountId id) {
        return statementOf(id).stream().map(LedgerEntry::type).toList();
    }

    /** Replaying an account's ledger must always reproduce its balance, even after a failed transfer. */
    private void assertLedgerReconciles(AccountId id) {
        Money replayed = statementOf(id).stream()
                .map(LedgerEntry::signedAmount)
                .reduce(Money.zero(Money.GBP), Money::plus);

        assertThat(replayed)
                .as("replaying the ledger of %s must reproduce its balance", id)
                .isEqualTo(balanceOf(id));
    }

    @Nested
    @DisplayName("when compliance approves")
    class Approved {

        @Test
        @DisplayName("moves the money and records one leg against each account")
        void completesTheTransfer() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "20.00");

            TransferReceipt receipt = saga.execute(ALICE, BOB, Money.gbp("30.00"), null);

            assertThat(balanceOf(ALICE)).isEqualTo(Money.gbp("70.00"));
            assertThat(balanceOf(BOB)).isEqualTo(Money.gbp("50.00"));

            assertThat(entryTypesOf(ALICE))
                    .containsExactly(TransactionType.DEPOSIT, TransactionType.TRANSFER_OUT);
            assertThat(entryTypesOf(BOB))
                    .containsExactly(TransactionType.DEPOSIT, TransactionType.TRANSFER_IN);

            assertThat(receipt.amount()).isEqualTo(Money.gbp("30.00"));
            assertThat(receipt.debit().reference()).isEqualTo(receipt.credit().reference());
        }

        @Test
        @DisplayName("writes no reversal, because nothing needed undoing")
        void writesNoReversal() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");

            saga.execute(ALICE, BOB, Money.gbp("30.00"), null);

            assertThat(entryTypesOf(ALICE)).doesNotContain(TransactionType.TRANSFER_REVERSAL);
        }

        @Test
        @DisplayName("screens the transfer with the same reference that appears on both legs")
        void screensWithTheTransferReference() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");

            TransferReceipt receipt = saga.execute(ALICE, BOB, Money.gbp("30.00"), null);

            assertThat(compliance.callCount()).isEqualTo(1);
            assertThat(compliance.lastCall().reference()).isEqualTo(receipt.reference());
            assertThat(compliance.lastCall().source()).isEqualTo(ALICE);
            assertThat(compliance.lastCall().destination()).isEqualTo(BOB);
            assertThat(compliance.lastCall().amount()).isEqualTo(Money.gbp("30.00"));
        }

        @Test
        @DisplayName("stamps both legs with a single instant, so they cannot drift apart")
        void stampsBothLegsWithOneInstant() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");

            TransferReceipt receipt = saga.execute(ALICE, BOB, Money.gbp("30.00"), null);

            assertThat(receipt.debit().occurredAt())
                    .isEqualTo(receipt.credit().occurredAt())
                    .isEqualTo(NOW);
        }
    }

    @Nested
    @DisplayName("when compliance refuses")
    class Rejected {

        @BeforeEach
        void complianceSaysNo() {
            compliance.reject("COUNTERPARTY_BLOCKED");
        }

        @Test
        @DisplayName("puts every penny back and leaves the destination untouched")
        void restoresBothBalances() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "20.00");

            assertThatThrownBy(() -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null))
                    .isInstanceOf(ComplianceRejectedException.class);

            assertThat(balanceOf(ALICE))
                    .as("a refused transfer must leave the payer exactly as they were")
                    .isEqualTo(Money.gbp("100.00"));
            assertThat(balanceOf(BOB))
                    .as("the destination must never be credited for a refused transfer")
                    .isEqualTo(Money.gbp("20.00"));
        }

        @Test
        @DisplayName("records the debit and its reversal, rather than erasing the attempt")
        void recordsTheAttemptAndItsReversal() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");

            assertThatThrownBy(() -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null))
                    .isInstanceOf(ComplianceRejectedException.class);

            // The ledger is append-only, so the honest record is that money left and came back.
            assertThat(entryTypesOf(ALICE)).containsExactly(
                    TransactionType.DEPOSIT,
                    TransactionType.TRANSFER_OUT,
                    TransactionType.TRANSFER_REVERSAL);

            // Opened at zero, so BOB has no opening entry either - and a refused transfer must not
            // have added one.
            assertThat(entryTypesOf(BOB)).isEmpty();
        }

        @Test
        @DisplayName("ties the reversal to the debit it undoes with a shared reference")
        void reversalSharesTheDebitReference() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");

            assertThatThrownBy(() -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null))
                    .isInstanceOf(ComplianceRejectedException.class);

            List<LedgerEntry> statement = statementOf(ALICE);
            LedgerEntry debit = statement.get(1);
            LedgerEntry reversal = statement.get(2);

            assertThat(reversal.reference()).isEqualTo(debit.reference());
            assertThat(reversal.amount()).isEqualTo(debit.amount());
        }

        @Test
        @DisplayName("explains on the statement why the money came back")
        void explainsTheReversal() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");

            assertThatThrownBy(() -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null))
                    .isInstanceOf(ComplianceRejectedException.class);

            assertThat(statementOf(ALICE).get(2).narrative())
                    .startsWith("Reversed:")
                    .contains("COUNTERPARTY_BLOCKED");
        }

        @Test
        @DisplayName("reports a definitive refusal that should not be retried")
        void reportsANonRetryableRefusal() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");

            ComplianceRejectedException thrown = assertThrows(ComplianceRejectedException.class,
                    () -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.COMPLIANCE_REJECTED);
            assertThat(thrown.details())
                    .containsEntry("reason", "COUNTERPARTY_BLOCKED")
                    .containsEntry("retryable", false);
        }

        @Test
        @DisplayName("leaves the ledger reconciling to the balance despite the round trip")
        void ledgerStillReconciles() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");

            assertThatThrownBy(() -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null))
                    .isInstanceOf(ComplianceRejectedException.class);

            assertLedgerReconciles(ALICE);
            assertLedgerReconciles(BOB);
        }
    }

    @Nested
    @DisplayName("when compliance cannot be reached")
    class Unavailable {

        @BeforeEach
        void complianceIsDown() {
            compliance.beUnavailable("CIRCUIT_OPEN");
        }

        @Test
        @DisplayName("refuses the transfer rather than allowing it through unscreened")
        void failsClosed() {
            // The whole point: an outage in a control must not silently disable the control.
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "20.00");

            assertThatThrownBy(() -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null))
                    .isInstanceOf(ComplianceUnavailableException.class);

            assertThat(balanceOf(ALICE)).isEqualTo(Money.gbp("100.00"));
            assertThat(balanceOf(BOB)).isEqualTo(Money.gbp("20.00"));
        }

        @Test
        @DisplayName("reports it as retryable, unlike a refusal")
        void reportsARetryableFailure() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");

            ComplianceUnavailableException thrown = assertThrows(ComplianceUnavailableException.class,
                    () -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.COMPLIANCE_UNAVAILABLE);
            assertThat(thrown.details()).containsEntry("retryable", true);
        }

        @Test
        @DisplayName("still reverses the debit, exactly as a refusal would")
        void stillReverses() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");

            assertThatThrownBy(() -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null))
                    .isInstanceOf(ComplianceUnavailableException.class);

            assertThat(entryTypesOf(ALICE)).endsWith(TransactionType.TRANSFER_REVERSAL);
            assertLedgerReconciles(ALICE);
        }
    }

    @Nested
    @DisplayName("when the transfer was never viable")
    class NeverViable {

        @Test
        @DisplayName("does not bother screening a transfer the source cannot afford")
        void doesNotScreenAnUnaffordableTransfer() {
            givenAccount(ALICE, "10.00");
            givenAccount(BOB, "0");

            assertThatThrownBy(() -> saga.execute(ALICE, BOB, Money.gbp("50.00"), null))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(compliance.callCount())
                    .as("no point asking compliance about money that is not there")
                    .isZero();
            assertThat(balanceOf(ALICE)).isEqualTo(Money.gbp("10.00"));
            assertThat(entryTypesOf(ALICE)).containsExactly(TransactionType.DEPOSIT);
        }

        @Test
        @DisplayName("refuses a transfer to a non-existent account without debiting first")
        void doesNotDebitTowardsAMissingAccount() {
            givenAccount(ALICE, "100.00");

            assertThatThrownBy(() -> saga.execute(ALICE, UNKNOWN, Money.gbp("10.00"), null))
                    .isInstanceOf(AccountNotFoundException.class);

            // Checking up front means the statement carries no debit-and-reversal pair for a payment
            // that was never possible.
            assertThat(entryTypesOf(ALICE)).containsExactly(TransactionType.DEPOSIT);
            assertThat(compliance.callCount()).isZero();
        }

        @Test
        @DisplayName("refuses a transfer from a non-existent account")
        void refusesUnknownSource() {
            givenAccount(BOB, "100.00");

            assertThatThrownBy(() -> saga.execute(UNKNOWN, BOB, Money.gbp("10.00"), null))
                    .isInstanceOf(AccountNotFoundException.class);

            assertThat(balanceOf(BOB)).isEqualTo(Money.gbp("100.00"));
        }

        @Test
        @DisplayName("refuses a transfer to the same account before anything happens")
        void refusesSameAccount() {
            givenAccount(ALICE, "100.00");

            assertThatThrownBy(() -> saga.execute(ALICE, ALICE, Money.gbp("10.00"), null))
                    .isInstanceOf(SameAccountTransferException.class);

            assertThat(compliance.callCount()).isZero();
            assertThat(entryTypesOf(ALICE)).containsExactly(TransactionType.DEPOSIT);
        }
    }

    @Nested
    @DisplayName("when the credit fails after approval")
    class CreditFails {

        @Test
        @DisplayName("reverses the debit so the payer is not left short")
        void reversesTheDebit() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "20.00");

            // Approved by compliance, but the destination's ledger write fails.
            ledger.failWhen(entry -> entry.type() == TransactionType.TRANSFER_IN);

            assertThatThrownBy(() -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null))
                    .isInstanceOf(FailableLedgerRepository.LedgerUnavailableException.class);

            assertThat(balanceOf(ALICE))
                    .as("the payer must not lose money because the credit leg failed")
                    .isEqualTo(Money.gbp("100.00"));
            assertThat(entryTypesOf(ALICE)).endsWith(TransactionType.TRANSFER_REVERSAL);
            assertLedgerReconciles(ALICE);
        }

        @Test
        @DisplayName("reports the underlying failure rather than blaming compliance")
        void reportsTheRealCause() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");
            ledger.failWhen(entry -> entry.type() == TransactionType.TRANSFER_IN);

            // Compliance approved, so a compliance error would send an investigator to the wrong place.
            assertThatThrownBy(() -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null))
                    .isNotInstanceOf(ComplianceRejectedException.class)
                    .isNotInstanceOf(ComplianceUnavailableException.class);
        }
    }

    @Nested
    @DisplayName("when even the reversal fails")
    class CompensationFails {

        @Test
        @DisplayName("admits the money is stranded instead of reporting a tidy refusal")
        void reportsStrandedFunds() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");

            compliance.reject("COUNTERPARTY_BLOCKED");
            // The reversal cannot be written either - the worst case a saga has.
            ledger.failWhen(entry -> entry.type() == TransactionType.TRANSFER_REVERSAL);

            TransferCompensationFailedException thrown = assertThrows(
                    TransferCompensationFailedException.class,
                    () -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.TRANSFER_COMPENSATION_FAILED);
            assertThat(thrown.details())
                    .containsEntry("accountId", "ACC-1001")
                    .containsEntry("amount", "30.00")
                    .containsEntry("retryable", false)
                    .containsEntry("requiresManualReconciliation", true);
        }

        @Test
        @DisplayName("does not pretend the transfer was merely refused")
        void doesNotMasqueradeAsARefusal() {
            givenAccount(ALICE, "100.00");
            givenAccount(BOB, "0");
            compliance.reject("COUNTERPARTY_BLOCKED");
            ledger.failWhen(entry -> entry.type() == TransactionType.TRANSFER_REVERSAL);

            // Reporting "compliance refused" would let the caller assume no money moved, when in fact
            // it has left the account. The more severe condition must win.
            assertThatThrownBy(() -> saga.execute(ALICE, BOB, Money.gbp("30.00"), null))
                    .isNotInstanceOf(ComplianceRejectedException.class);

            // The debit stands on the statement with no reversal beside it. That absence is exactly
            // what makes this state need reconciliation, and what a recovery process would look for.
            //
            // The resulting *balance* is deliberately not asserted here: these are plain objects, so
            // the step's @Transactional is inert and a half-applied step is not well defined. In the
            // running service the reversal step either commits whole or rolls back, so the debit
            // stands and the ledger agrees with it.
            assertThat(entryTypesOf(ALICE))
                    .containsExactly(TransactionType.DEPOSIT, TransactionType.TRANSFER_OUT)
                    .doesNotContain(TransactionType.TRANSFER_REVERSAL);
        }
    }

    @Nested
    @DisplayName("across repeated attempts")
    class Repeatedly {

        @Test
        @DisplayName("conserves money whatever the mix of outcomes")
        void conservesMoneyAcrossMixedOutcomes() {
            givenAccount(ALICE, "500.00");
            givenAccount(BOB, "500.00");

            compliance.approve();
            saga.execute(ALICE, BOB, Money.gbp("50.00"), null);

            compliance.reject("SINGLE_TRANSFER_LIMIT_EXCEEDED");
            assertThatThrownBy(() -> saga.execute(ALICE, BOB, Money.gbp("60.00"), null))
                    .isInstanceOf(ComplianceRejectedException.class);

            compliance.beUnavailable("SCREENING_CALL_FAILED");
            assertThatThrownBy(() -> saga.execute(BOB, ALICE, Money.gbp("70.00"), null))
                    .isInstanceOf(ComplianceUnavailableException.class);

            compliance.approve();
            saga.execute(BOB, ALICE, Money.gbp("25.00"), null);

            assertThat(balanceOf(ALICE).plus(balanceOf(BOB)))
                    .as("transfers move money; they never create or destroy it")
                    .isEqualTo(Money.gbp("1000.00"));
            assertThat(balanceOf(ALICE)).isEqualTo(Money.gbp("475.00"));
            assertThat(balanceOf(BOB)).isEqualTo(Money.gbp("525.00"));

            assertLedgerReconciles(ALICE);
            assertLedgerReconciles(BOB);
        }
    }
}
