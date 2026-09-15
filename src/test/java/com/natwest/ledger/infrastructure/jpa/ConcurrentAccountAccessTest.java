package com.natwest.ledger.infrastructure.jpa;

import com.natwest.ledger.application.AccountService;
import com.natwest.ledger.application.ProgrammableComplianceGateway;
import com.natwest.ledger.application.TransactionService;
import com.natwest.ledger.application.TransferCompensationFailedException;
import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.InsufficientFundsException;
import com.natwest.ledger.domain.LedgerEntry;
import com.natwest.ledger.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies that the account rules survive concurrency, not just single-threaded use.
 *
 * <p>This is the test that justifies the whole of Phase 4. Every rule in {@code Account} is enforced
 * on an in-memory object, which is worthless if two requests can each load a balance of 100, each
 * independently conclude that 75 is affordable, and each write its own answer - leaving 25 where the
 * truth is that one of them should have been refused. That is a lost update, and no amount of
 * validation inside the aggregate prevents it. Only the version check does.
 *
 * <p>The assertions are deliberately written as invariants rather than as exact outcomes. Thread
 * interleaving is not deterministic, so "exactly four withdrawals succeed" would be a flaky test.
 * "The final balance equals the opening balance minus the amount actually withdrawn, and is never
 * negative" is true on every possible interleaving - and it is the property a bank actually cares
 * about.
 */
@SpringBootTest
@DisplayName("An account under concurrent access")
class ConcurrentAccountAccessTest {

    private static final AccountId ALICE = AccountId.of("ACC-1001");
    private static final AccountId BOB = AccountId.of("ACC-2002");

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Approves every transfer, so this test measures database contention rather than compliance.
     *
     * <p>Without it the real gateway would try to reach another process, fail, and every transfer would
     * be refused - which would still conserve money, but only because none of it ever moved. The
     * concurrency being tested here is the ledger's, not the dependency's.
     */
    @TestConfiguration
    static class ApprovingComplianceConfiguration {

        @Bean
        @Primary
        ProgrammableComplianceGateway approvingComplianceGateway() {
            return new ProgrammableComplianceGateway();
        }
    }

    @BeforeEach
    void clearTables() {
        jdbcTemplate.execute("DELETE FROM ledger_entry");
        jdbcTemplate.execute("DELETE FROM account");
    }

    /**
     * Runs every task at once, releasing them from a common starting gate so they genuinely contend
     * rather than trickling past each other.
     */
    private static <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** What happened to one attempt. */
    private enum Outcome {
        APPLIED,
        REFUSED_INSUFFICIENT_FUNDS,
        LOST_THE_RACE,

        /** Money debited and not put back. Must never happen; asserted against explicitly. */
        FUNDS_STRANDED
    }

    @Test
    @DisplayName("cannot be overdrawn by simultaneous withdrawals, however they interleave")
    void concurrentWithdrawalsCannotOverdraw() throws Exception {
        accountService.openAccount(ALICE, "Ada Lovelace", Money.gbp("100.00"));

        int attempts = 10;
        Money each = Money.gbp("25.00");

        List<Outcome> outcomes = runConcurrently(attempts, () -> {
            try {
                transactionService.withdraw(ALICE, each, "Concurrent withdrawal");
                return Outcome.APPLIED;
            } catch (InsufficientFundsException e) {
                return Outcome.REFUSED_INSUFFICIENT_FUNDS;
            } catch (OptimisticLockingFailureException e) {
                // Another attempt committed first. Nothing was applied, which is the correct and safe
                // outcome - this is the cost side of optimistic locking's bargain.
                return Outcome.LOST_THE_RACE;
            }
        });

        long applied = outcomes.stream().filter(o -> o == Outcome.APPLIED).count();
        Money finalBalance = accountService.balanceOf(ALICE);

        assertThat(finalBalance.isNegative())
                .as("the balance must never go below zero, whatever the interleaving")
                .isFalse();

        assertThat(applied)
                .as("at most four withdrawals of 25.00 can come out of 100.00")
                .isBetween(1L, 4L);

        // The heart of it: if any update had been lost, money would have vanished from this equation.
        Money withdrawn = Money.of(each.amount().multiply(BigDecimal.valueOf(applied)), Money.GBP);
        assertThat(finalBalance)
                .as("balance must equal 100.00 minus exactly the amount actually withdrawn")
                .isEqualTo(Money.gbp("100.00").minus(withdrawn));

        // And the ledger must agree: one entry per applied withdrawal, plus the opening balance.
        List<LedgerEntry> history = accountService.transactionHistory(ALICE);
        assertThat(history).hasSize((int) applied + 1);

        Money replayed = history.stream()
                .map(LedgerEntry::signedAmount)
                .reduce(Money.zero(Money.GBP), Money::plus);
        assertThat(replayed)
                .as("replaying the ledger must reproduce the balance even after contention")
                .isEqualTo(finalBalance);
    }

    @Test
    @DisplayName("loses no deposit when simultaneous credits are retried")
    void concurrentDepositsAreNeverLost() throws Exception {
        accountService.openAccount(ALICE, "Ada Lovelace", Money.gbp("0"));

        int attempts = 10;
        AtomicInteger retries = new AtomicInteger();

        // Retrying on a lost race is the other half of the optimistic bargain, and it is what a
        // caller (or a @Retryable wrapper) would do in production. With it, every single deposit
        // must land - none may be silently dropped or overwritten.
        List<Outcome> outcomes = runConcurrently(attempts, () -> {
            for (int attempt = 0; attempt < 50; attempt++) {
                try {
                    transactionService.deposit(ALICE, Money.gbp("10.00"), "Concurrent deposit");
                    return Outcome.APPLIED;
                } catch (OptimisticLockingFailureException e) {
                    retries.incrementAndGet();
                }
            }
            return Outcome.LOST_THE_RACE;
        });

        assertThat(outcomes).allMatch(o -> o == Outcome.APPLIED);

        assertThat(accountService.balanceOf(ALICE))
                .as("every one of the %d deposits must be reflected in the balance", attempts)
                .isEqualTo(Money.gbp("100.00"));

        assertThat(accountService.transactionHistory(ALICE))
                .as("and each must appear exactly once on the statement")
                .hasSize(attempts);
    }

    @Test
    @DisplayName("conserves money when transfers run in both directions at once")
    void concurrentTransfersConserveMoney() throws Exception {
        accountService.openAccount(ALICE, "Ada Lovelace", Money.gbp("500.00"));
        accountService.openAccount(BOB, "Grace Hopper", Money.gbp("500.00"));

        int attempts = 12;

        // Opposing transfers under contention. Each saga step touches one account, so the two-row
        // deadlock is structurally impossible now - but the steps can still lose optimistic-lock races
        // against each other, which is what makes this the test that matters for compensation.
        List<Outcome> outcomes = runConcurrently(attempts, () -> {
            boolean aliceToBob = Thread.currentThread().getId() % 2 == 0;
            try {
                if (aliceToBob) {
                    transactionService.transfer(ALICE, BOB, Money.gbp("10.00"), null);
                } else {
                    transactionService.transfer(BOB, ALICE, Money.gbp("10.00"), null);
                }
                return Outcome.APPLIED;
            } catch (TransferCompensationFailedException e) {
                return Outcome.FUNDS_STRANDED;
            } catch (OptimisticLockingFailureException e) {
                return Outcome.LOST_THE_RACE;
            } catch (InsufficientFundsException e) {
                return Outcome.REFUSED_INSUFFICIENT_FUNDS;
            }
        });

        // The guarantee that justifies retrying compensation. Before it was added, the credit step would
        // lose a race, compensation would immediately lose one too, and money was stranded routinely
        // rather than rarely.
        assertThat(outcomes)
                .as("no transfer may end with money debited and not put back")
                .doesNotContain(Outcome.FUNDS_STRANDED);

        Money total = accountService.balanceOf(ALICE).plus(accountService.balanceOf(BOB));

        assertThat(total)
                .as("a transfer moves money, it never creates or destroys it")
                .isEqualTo(Money.gbp("1000.00"));

        assertThat(accountService.balanceOf(ALICE).isNegative()).isFalse();
        assertThat(accountService.balanceOf(BOB).isNegative()).isFalse();

        assertLedgerReconciles(ALICE);
        assertLedgerReconciles(BOB);
    }

    /** Replaying an account's ledger must reproduce its balance, even after contended transfers. */
    private void assertLedgerReconciles(AccountId accountId) {
        Money replayed = accountService.transactionHistory(accountId).stream()
                .map(LedgerEntry::signedAmount)
                .reduce(Money.zero(Money.GBP), Money::plus);

        assertThat(replayed)
                .as("replaying the ledger of %s must reproduce its balance", accountId)
                .isEqualTo(accountService.balanceOf(accountId));
    }
}
