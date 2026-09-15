package com.natwest.compliance.domain;

import com.natwest.compliance.config.ComplianceProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Specifies the screening rules.
 *
 * <p>These are the statements a compliance officer would want to read: what gets stopped, what gets
 * through, and exactly where the boundary sits.
 */
@DisplayName("The screening policy")
class ScreeningPolicyTest {

    private static final String ALICE = "ACC-1001";
    private static final String BOB = "ACC-2002";

    private static ScreeningPolicy policyWith(String limit, String... blocked) {
        return new ScreeningPolicy(new ComplianceProperties(new BigDecimal(limit), List.of(blocked)));
    }

    private static final ScreeningPolicy POLICY = policyWith("10000.00", "ACC-BLOCKED");

    @Nested
    @DisplayName("on the value of a transfer")
    class ValueLimit {

        @Test
        @DisplayName("approves an ordinary transfer")
        void approvesOrdinaryTransfer() {
            ScreeningOutcome outcome = POLICY.screen(ALICE, BOB, new BigDecimal("250.00"));

            assertThat(outcome.isApproved()).isTrue();
            assertThat(outcome.decision()).isEqualTo(Decision.APPROVED);
            assertThat(outcome.reason()).isNull();
        }

        @Test
        @DisplayName("approves a transfer one penny under the limit")
        void approvesJustUnderTheLimit() {
            assertThat(POLICY.screen(ALICE, BOB, new BigDecimal("9999.99")).isApproved()).isTrue();
        }

        @Test
        @DisplayName("rejects a transfer at exactly the limit, because a limit is a ceiling")
        void rejectsExactlyAtTheLimit() {
            // The inclusive boundary is the whole point of this test. An exclusive check would leave a
            // control that can be walked right up to, and off-by-one here is a real finding.
            ScreeningOutcome outcome = POLICY.screen(ALICE, BOB, new BigDecimal("10000.00"));

            assertThat(outcome.decision()).isEqualTo(Decision.REJECTED);
            assertThat(outcome.reason()).isEqualTo(ScreeningPolicy.SINGLE_TRANSFER_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("rejects a transfer above the limit")
        void rejectsAboveTheLimit() {
            assertThat(POLICY.screen(ALICE, BOB, new BigDecimal("25000.00")).decision())
                    .isEqualTo(Decision.REJECTED);
        }

        @Test
        @DisplayName("honours a limit changed by configuration, without a code change")
        void honoursConfiguredLimit() {
            ScreeningPolicy strict = policyWith("100.00");

            assertThat(strict.screen(ALICE, BOB, new BigDecimal("150.00")).decision())
                    .isEqualTo(Decision.REJECTED);
            assertThat(strict.screen(ALICE, BOB, new BigDecimal("99.99")).isApproved()).isTrue();
        }
    }

    @Nested
    @DisplayName("on blocked counterparties")
    class BlockedCounterparties {

        @Test
        @DisplayName("rejects paying a blocked party")
        void rejectsPayingBlockedParty() {
            ScreeningOutcome outcome = POLICY.screen(ALICE, "ACC-BLOCKED", new BigDecimal("1.00"));

            assertThat(outcome.decision()).isEqualTo(Decision.REJECTED);
            assertThat(outcome.reason()).isEqualTo(ScreeningPolicy.COUNTERPARTY_BLOCKED);
        }

        @Test
        @DisplayName("rejects a blocked party paying out, since a block works in both directions")
        void rejectsBlockedPartyPayingOut() {
            // Screening only the destination would let a blocked party empty its account freely,
            // which defeats the purpose of blocking it.
            assertThat(POLICY.screen("ACC-BLOCKED", BOB, new BigDecimal("1.00")).reason())
                    .isEqualTo(ScreeningPolicy.COUNTERPARTY_BLOCKED);
        }

        @ParameterizedTest(name = "still blocks \"{0}\"")
        @DisplayName("cannot be bypassed by changing the case of the identifier")
        @ValueSource(strings = {"acc-blocked", "Acc-Blocked", "ACC-blocked", "  ACC-BLOCKED  "})
        void cannotBeBypassedByCase(String variant) {
            assertThat(POLICY.screen(ALICE, variant, new BigDecimal("1.00")).reason())
                    .isEqualTo(ScreeningPolicy.COUNTERPARTY_BLOCKED);
        }

        @Test
        @DisplayName("reports the block rather than the limit when both rules are broken")
        void prefersBlockOverLimit() {
            // "We will not deal with this party at all" is the stronger statement. Reporting the limit
            // instead would hint that a smaller amount to the same party might get through.
            assertThat(POLICY.screen(ALICE, "ACC-BLOCKED", new BigDecimal("50000.00")).reason())
                    .isEqualTo(ScreeningPolicy.COUNTERPARTY_BLOCKED);
        }

        @Test
        @DisplayName("blocks nobody when no list is configured")
        void blocksNobodyByDefault() {
            ScreeningPolicy permissive = policyWith("10000.00");

            assertThat(permissive.screen(ALICE, "ACC-BLOCKED", new BigDecimal("1.00")).isApproved()).isTrue();
        }
    }

    @Nested
    @DisplayName("on the shape of an outcome")
    class OutcomeInvariants {

        @Test
        @DisplayName("insists that a rejection explains itself")
        void rejectionMustHaveReason() {
            assertThatThrownBy(() -> ScreeningOutcome.rejected("  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must state a reason");
        }

        @Test
        @DisplayName("carries no reason when approved")
        void approvalHasNoReason() {
            assertThat(ScreeningOutcome.approved().reason()).isNull();
        }
    }
}
