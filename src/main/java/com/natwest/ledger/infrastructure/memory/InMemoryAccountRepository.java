package com.natwest.ledger.infrastructure.memory;

import com.natwest.ledger.application.AccountRepository;
import com.natwest.ledger.domain.Account;
import com.natwest.ledger.domain.AccountId;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory account store.
 *
 * <p>Present so the application layer could be built and tested end to end before committing to a
 * database. Phase 4 adds the JPA adapter and confines this one to the {@code memory} profile;
 * because both sit behind {@link AccountRepository}, that swap changes no business logic.
 *
 * <p><b>It hands out copies, not references.</b> A naive map-backed repository returns the same
 * mutable {@link Account} instance it stored, so a caller that mutates an account and forgets to
 * call {@code save} still sees its change persist - and the test passes for the wrong reason, then
 * fails against a real database. Copying on both read and write reproduces the isolation a database
 * gives, so a missing {@code save} is caught here rather than in production.
 */
@Repository
@Profile("!jpa")
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
