package com.natwest.ledger.repository.memory;

import com.natwest.ledger.model.Account;
import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.repository.AccountRepository;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory account store.
 *
 * <p>Present so the application layer could be built and tested end to end before committing to a
 * database. Now that the JPA adapter exists it is the default and this one is opt-in under the
 * {@code memory} profile - a banking service should persist unless told otherwise. Not one line of
 * business logic changed when the database arrived, which was the point of putting a port here.
 *
 * <p>Retained rather than deleted because it keeps the fast unit tests free of a database, and
 * because it is the clearest possible demonstration that the application layer really is unaware of
 * how storage works.
 *
 * <p><b>It hands out copies, not references.</b> A naive map-backed repository returns the same
 * mutable {@link Account} instance it stored, so a caller that mutates an account and forgets to
 * call {@code save} still sees its change persist - and the test passes for the wrong reason, then
 * fails against a real database. Copying on both read and write reproduces the isolation a database
 * gives, so a missing {@code save} is caught here rather than in production.
 */
@Repository
@Profile("memory")
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<AccountId, Account> accountsById = new ConcurrentHashMap<>();

    @Override
    public Optional<Account> findById(AccountId accountId) {
        return Optional.ofNullable(accountsById.get(accountId)).map(InMemoryAccountRepository::copyOf);
    }

    @Override
    public boolean existsById(AccountId accountId) {
        return accountsById.containsKey(accountId);
    }

    @Override
    public Account save(Account account) {
        accountsById.put(account.id(), copyOf(account));
        return copyOf(account);
    }

    private static Account copyOf(Account account) {
        return Account.reconstitute(account.id(), account.holderName(), account.balance());
    }

    /** Clears the store. For tests that want a fresh slate without rebuilding the context. */
    public void deleteAll() {
        accountsById.clear();
    }
}

