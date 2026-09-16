package com.natwest.ledger.repository;

import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.model.LedgerEntry;

import java.util.List;
import java.util.function.Predicate;

/**
 * A ledger that can be told to fail when a particular kind of entry is written.
 *
 * <p>Needed to test the parts of the saga that only run when something goes wrong halfway through.
 * Compensation and, worse, failed compensation are the paths most likely to be broken and least likely
 * to be exercised by accident, so they need a way to be provoked deliberately.
 *
 * <p>Failing on the entry type rather than on a call count keeps the tests readable: "fail when the
 * credit is written" states the scenario, whereas "fail on the third write" would silently target a
 * different step the moment the saga's sequence changed.
 */
public class FailableLedgerRepository implements LedgerRepository {

    /** Thrown to simulate the ledger being unable to accept a write. */
    public static class LedgerUnavailableException extends RuntimeException {
        LedgerUnavailableException(LedgerEntry entry) {
            super("simulated ledger failure writing a " + entry.type() + " entry");
        }
    }

    private final LedgerRepository delegate;
    private volatile Predicate<LedgerEntry> failWhen = entry -> false;

    public FailableLedgerRepository(LedgerRepository delegate) {
        this.delegate = delegate;
    }

    public void failWhen(Predicate<LedgerEntry> condition) {
        this.failWhen = condition;
    }

    public void stopFailing() {
        this.failWhen = entry -> false;
    }

    @Override
    public LedgerEntry append(LedgerEntry entry) {
        if (failWhen.test(entry)) {
            throw new LedgerUnavailableException(entry);
        }
        return delegate.append(entry);
    }

    @Override
    public List<LedgerEntry> appendAll(List<LedgerEntry> entries) {
        entries.stream().filter(failWhen).findFirst().ifPresent(entry -> {
            throw new LedgerUnavailableException(entry);
        });
        return delegate.appendAll(entries);
    }

    @Override
    public List<LedgerEntry> findByAccountId(AccountId accountId) {
        return delegate.findByAccountId(accountId);
    }

    @Override
    public List<LedgerEntry> findByAccountId(AccountId accountId, int offset, int limit) {
        return delegate.findByAccountId(accountId, offset, limit);
    }

    @Override
    public long countByAccountId(AccountId accountId) {
        return delegate.countByAccountId(accountId);
    }
}

