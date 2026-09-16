package com.natwest.ledger.repository.jpa;

import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.model.LedgerEntry;
import com.natwest.ledger.repository.LedgerRepository;

import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The JPA adapter for {@link LedgerRepository}.
 *
 * <p>Insert-only, mirroring the port. There is no {@code update} or {@code delete} to implement,
 * so the append-only guarantee is structural rather than a rule somebody has to remember.
 *
 * <p>Paging is translated from offset/limit into a Spring Data {@code PageRequest} here, at the
 * edge, so that Spring Data's types never appear in the application layer.
 */
@Repository
@Profile("!memory")
public class JpaLedgerRepository implements LedgerRepository {

    private final SpringDataLedgerEntryRepository entries;

    JpaLedgerRepository(SpringDataLedgerEntryRepository entries) {
        this.entries = entries;
    }

    @Override
    public LedgerEntry append(LedgerEntry entry) {
        return entries.save(LedgerEntryEntity.from(entry)).toDomain();
    }

    /**
     * Appends both legs of a transfer.
     *
     * <p>Atomicity comes from the caller's transaction, not from this method: the service is
     * {@code @Transactional}, so either both rows commit or neither does. Trying to guarantee it here
     * would be the wrong place - the balance updates that accompany these entries also have to be in
     * the same unit of work.
     */
    @Override
    public List<LedgerEntry> appendAll(List<LedgerEntry> batch) {
        List<LedgerEntryEntity> rows = batch.stream().map(LedgerEntryEntity::from).toList();
        return entries.saveAll(rows).stream().map(LedgerEntryEntity::toDomain).toList();
    }

    @Override
    public List<LedgerEntry> findByAccountId(AccountId accountId) {
        return entries.findByAccountIdOrderBySequenceAsc(accountId.value())
                .stream().map(LedgerEntryEntity::toDomain).toList();
    }

    @Override
    public List<LedgerEntry> findByAccountId(AccountId accountId, int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        // PageRequest is page-based, so an arbitrary offset is expressed as a single-page window
        // starting at that offset. Requires offset to be a multiple of limit, which the application
        // layer guarantees by deriving offset from page * size.
        PageRequest window = PageRequest.of(offset / limit, limit);

        return entries.findByAccountIdOrderBySequenceAsc(accountId.value(), window)
                .stream().map(LedgerEntryEntity::toDomain).toList();
    }

    @Override
    public long countByAccountId(AccountId accountId) {
        return entries.countByAccountId(accountId.value());
    }
}

