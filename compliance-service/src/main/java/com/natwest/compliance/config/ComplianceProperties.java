package com.natwest.compliance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The screening rules, externalised.
 *
 * <p>Compliance thresholds and counterparty lists change for regulatory reasons, on a timescale
 * entirely unrelated to software releases. Hard-coding them would mean a code change, a review and a
 * deployment every time a limit moved, so they are configuration.
 *
 * @param singleTransferLimit the largest transfer that may proceed without rejection
 * @param blockedAccounts     account identifiers that may not send or receive
 */
@ConfigurationProperties(prefix = "compliance")
public record ComplianceProperties(BigDecimal singleTransferLimit, List<String> blockedAccounts) {

    private static final BigDecimal DEFAULT_SINGLE_TRANSFER_LIMIT = new BigDecimal("10000.00");

    public ComplianceProperties {
        singleTransferLimit = singleTransferLimit == null
                ? DEFAULT_SINGLE_TRANSFER_LIMIT
                : singleTransferLimit;
        blockedAccounts = blockedAccounts == null ? List.of() : List.copyOf(blockedAccounts);
    }

    /**
     * The blocked list, normalised to upper case.
     *
     * <p>Matches the ledger service's own normalisation of account identifiers. A blocked list that
     * is case-sensitive when identifiers are not would be trivially bypassed by changing the case of
     * a single letter.
     */
    public Set<String> normalisedBlockedAccounts() {
        return blockedAccounts.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(id -> id.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
