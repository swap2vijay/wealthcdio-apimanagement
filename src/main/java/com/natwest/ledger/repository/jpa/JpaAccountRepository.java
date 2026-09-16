package com.natwest.ledger.repository.jpa;

import com.natwest.ledger.model.Account;
import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.repository.AccountRepository;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * The JPA adapter for {@link AccountRepository}.
 *
 * <p>Active by default. A banking service should persist by default, so the in-memory adapter is now
 * opt-in under the {@code memory} profile rather than the other way round.
 *
 * <p><b>{@code save} loads before it writes, and that is deliberate.</b> Looking the row up again
 * inside the same transaction returns the instance already in the persistence context, complete with
 * the version it was read at. Applying the new balance to that managed instance is what lets
 * Hibernate emit {@code UPDATE ... WHERE version = ?} and detect a concurrent change. Building a
 * detached entity from the domain object instead would carry no version, silently turning the
 * optimistic check into a last-writer-wins overwrite - the precise bug this phase exists to prevent.
 *
 * <p>This also means the service methods must be transactional: without a transaction spanning load
 * and save, each call gets its own persistence context and there is no version to compare against.
 */
@Repository
@Profile("!memory")
public class JpaAccountRepository implements AccountRepository {

    private final SpringDataAccountRepository accounts;

    JpaAccountRepository(SpringDataAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<Account> findById(AccountId accountId) {
        return accounts.findById(accountId.value()).map(AccountEntity::toDomain);
    }

    @Override
    public boolean existsById(AccountId accountId) {
        return accounts.existsById(accountId.value());
    }

    @Override
    public Account save(Account account) {
        AccountEntity entity = accounts.findById(account.id().value())
                .orElseGet(() -> AccountEntity.newFor(account));

        entity.apply(account);

        return accounts.save(entity).toDomain();
    }
}

