package com.natwest.ledger.repository;

import com.natwest.ledger.model.Account;
import com.natwest.ledger.model.AccountId;

import java.util.Optional;

/**
 * The application's view of account storage.
 *
 * <p>Declared here, in the layer that <em>uses</em> it, rather than in the layer that implements
 * it. That inversion is what lets the domain and application code be written and fully tested
 * before any decision about a database has been made - and why introducing JPA in Phase 4 changes
 * no business logic at all, only which adapter is wired in.
 *
 * <p>Speaks exclusively in domain types. No JPA entity, {@code ResultSet} or row mapping is
 * visible from this side of the boundary.
 */
public interface AccountRepository {

    /** Loads an account, or empty if no account holds that identifier. */
    Optional<Account> findById(AccountId accountId);

    /** True when an account already holds that identifier. Used to enforce id uniqueness. */
    boolean existsById(AccountId accountId);

    /** Persists the current state of an account, inserting or updating as required. */
    Account save(Account account);
}

