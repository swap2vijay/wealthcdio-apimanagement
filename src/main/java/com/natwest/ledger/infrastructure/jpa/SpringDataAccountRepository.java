package com.natwest.ledger.infrastructure.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the account table.
 *
 * <p>Kept separate from {@link JpaAccountRepository}, which is the adapter that implements the
 * application's port. This interface is a persistence detail speaking in entities; the adapter is
 * what translates it into domain terms. Merging the two would push {@code JpaRepository}'s whole
 * API surface - {@code deleteAll}, {@code findAll}, paging - into the application layer, along with
 * a Spring Data dependency the domain has no business knowing about.
 */
public interface SpringDataAccountRepository extends JpaRepository<AccountEntity, String> {
}
