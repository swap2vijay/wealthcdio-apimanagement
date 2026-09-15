package com.natwest.ledger.infrastructure.memory;

import com.natwest.ledger.application.LedgerRepository;
import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.LedgerEntry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory, append-only ledger.
 *
 * <p>Entries need no defensive copying: {@link LedgerEntry} is an immutable record, so sharing a
 * reference cannot leak a mutation. The returned <em>lists</em> are copies, so a caller cannot
 * append to an account's history by mutating a list it was handed.
 *
 * <p>Keyed by account because that is the only access pattern the requirements ask for - "a ledger
 * of transactions per account". A single global list would have to be filtered on every read.
 */
@Repository
@Profile("memory")
public class InMemoryLedgerRepository implements LedgerRepository {

    private final Map<AccountId, List<LedgerEntry>> entriesByAccount = new ConcurrentHashMap<>();

    @Override
    public LedgerEntry append(LedgerEntry entry) {
        entriesByAccount
                .computeIfAbsent(entry.accountId(), id -> Collections.synchronizedList(new ArrayList<>()))
                .add(entry);
        return entry;
    }

    /**
     * Appends every entry, or none.
     *
     * <p>Validates the whole batch before writing any of it. Appending as it goes would let a
     * malformed third entry leave the first two committed - exactly the half-written transfer the
     * batch API exists to prevent.
     */
    @Override
    public List<LedgerEntry> appendAll(List<LedgerEntry> entries) {
        List<LedgerEntry> batch = List.copyOf(entries);
        batch.forEach(entry -> {
            if (entry == null) {
                throw new IllegalArgumentException("A ledger batch must not contain a null entry");
            }
        });
        batch.forEach(this::append);
        return batch;
    }

    @Override
    public List<LedgerEntry> findByAccountId(AccountId accountId) {
        List<LedgerEntry> entries = entriesByAccount.get(accountId);
        if (entries == null) {
            return List.of();
        }
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    /**
     * A window of an account's entries.
     *
     * <p>Insertion order is the total order here, which matches what the JPA adapter achieves with its
     * sequence column. Slicing an already-loaded list is not real paging - it saves no work - but this
     * adapter exists for tests, and behaving identically to the real one is what makes those tests
     * meaningful.
     */
    @Override
    public List<LedgerEntry> findByAccountId(AccountId accountId, int offset, int limit) {
        if (limit <= 0 || offset < 0) {
            return List.of();
        }
        List<LedgerEntry> all = findByAccountId(accountId);
        if (offset >= all.size()) {
            return List.of();
        }
        return List.copyOf(all.subList(offset, Math.min(offset + limit, all.size())));
    }

    @Override
    public long countByAccountId(AccountId accountId) {
        return findByAccountId(accountId).size();
    }

    /** Clears the ledger. For tests that want a fresh slate without rebuilding the context. */
    public void deleteAll() {
        entriesByAccount.clear();
    }
}
