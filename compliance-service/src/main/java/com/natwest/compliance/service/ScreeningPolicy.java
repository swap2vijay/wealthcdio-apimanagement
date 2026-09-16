package com.natwest.compliance.service;

import com.natwest.compliance.config.ComplianceProperties;
import com.natwest.compliance.model.ScreeningOutcome;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * The rules that decide whether a transfer may proceed.
 *
 * <p>Pure and synchronous: given the same inputs it always returns the same decision, with no
 * database, clock or network involved. That is what makes compliance decisions reproducible - being
 * able to explain months later why a particular payment was stopped matters more here than in most
 * code.
 *
 * <p>Rule order is deliberate. A blocked counterparty is checked before the value limit, because
 * "we will not deal with this party at all" is a stronger and more useful statement than "this
 * amount is too large" - and reporting the limit first would tell someone probing the system that
 * a smaller amount to the same party might get through.
 */
@Component
public class ScreeningPolicy {

    /** The counterparty is on a list of parties this bank will not transact with. */
    public static final String COUNTERPARTY_BLOCKED = "COUNTERPARTY_BLOCKED";

    /** The value exceeds what may move in a single transfer without manual authorisation. */
    public static final String SINGLE_TRANSFER_LIMIT_EXCEEDED = "SINGLE_TRANSFER_LIMIT_EXCEEDED";

    private final ComplianceProperties properties;

    public ScreeningPolicy(ComplianceProperties properties) {
        this.properties = properties;
    }

    /**
     * Screens a proposed transfer.
     *
     * @param sourceAccountId      who is paying
     * @param destinationAccountId who is being paid
     * @param amount               how much is moving
     */
    public ScreeningOutcome screen(String sourceAccountId, String destinationAccountId, BigDecimal amount) {
        Set<String> blocked = properties.normalisedBlockedAccounts();

        // Both ends are checked. Screening only the destination would let a blocked party move money
        // out freely, which is precisely what a block is meant to prevent.
        if (blocked.contains(normalise(sourceAccountId)) || blocked.contains(normalise(destinationAccountId))) {
            return ScreeningOutcome.rejected(COUNTERPARTY_BLOCKED);
        }

        // Inclusive: a transfer exactly at the limit is refused. Limits in banking are ceilings that
        // must not be reached, and an off-by-one here is the difference between a control that holds
        // and one that can be walked right up to.
        if (amount.compareTo(properties.singleTransferLimit()) >= 0) {
            return ScreeningOutcome.rejected(SINGLE_TRANSFER_LIMIT_EXCEEDED);
        }

        return ScreeningOutcome.approved();
    }

    private static String normalise(String accountId) {
        return accountId == null ? "" : accountId.trim().toUpperCase(Locale.ROOT);
    }
}

