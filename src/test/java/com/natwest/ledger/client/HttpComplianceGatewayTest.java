package com.natwest.ledger.client;

import com.natwest.ledger.config.ComplianceClientConfiguration;
import com.natwest.ledger.config.ComplianceClientProperties;
import com.natwest.ledger.exception.ComplianceContractException;
import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.model.Money;
import com.natwest.ledger.model.TransactionReference;
import com.natwest.ledger.observability.CorrelationId;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies how the ledger service behaves when compliance is healthy, refusing, slow, broken, or gone.
 *
 * <p>These are the cases that only exist because the check crossed a process boundary, and they are the
 * ones most likely to be wrong. Each asserts on the number of requests the stub actually received as
 * well as on the outcome, because "returned an error" and "did not call a failing dependency at all"
 * look identical from the outside and are the whole difference a circuit breaker makes.
 *
 * <p>Timings are compressed relative to production: two attempts instead of three, millisecond
 * backoffs, and a breaker that judges on two calls. The behaviour under test is the wiring, not the
 * numbers - the production numbers are asserted separately against the real configuration.
 */
@DisplayName("The compliance gateway")
class HttpComplianceGatewayTest {

    private static final TransactionReference REFERENCE = TransactionReference.of("REF-1");
    private static final AccountId ALICE = AccountId.of("ACC-1001");
    private static final AccountId BOB = AccountId.of("ACC-2002");
    private static final Money AMOUNT = Money.gbp("250.00");

    private static final int MAX_ATTEMPTS = 2;

    private StubComplianceService compliance;
    private HttpComplianceGateway gateway;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        compliance = new StubComplianceService();

        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                // Mirrors production: our own malformed request must not be read as the dependency
                // being unwell.
                .ignoreExceptions(ComplianceContractException.class)
                .build());

        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(MAX_ATTEMPTS)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(ComplianceContractException.class)
                .build());

        circuitBreaker = breakers.circuitBreaker(HttpComplianceGateway.INSTANCE_NAME);

        // Built through the production configuration class, so the timeout wiring is under test too.
        var restClient = new ComplianceClientConfiguration().complianceRestClient(
                new ComplianceClientProperties(compliance.baseUrl(),
                        Duration.ofMillis(250), Duration.ofMillis(400)));

        gateway = new HttpComplianceGateway(restClient, breakers, retries);
    }

    @AfterEach
    void tearDown() {
        compliance.close();
    }

    private ComplianceAssessment screen() {
        return gateway.screen(REFERENCE, ALICE, BOB, AMOUNT);
    }

    @Nested
    @DisplayName("when compliance answers")
    class HappyPath {

        @Test
        @DisplayName("reports an approval")
        void reportsApproval() {
            compliance.alwaysRespondWith(StubComplianceService.Reply.ok(
                    StubComplianceService.approved("REF-1")));

            ComplianceAssessment assessment = screen();

            assertThat(assessment.outcome()).isEqualTo(ComplianceOutcome.APPROVED);
            assertThat(assessment.isApproved()).isTrue();
            assertThat(assessment.reference()).isEqualTo(REFERENCE);
            assertThat(compliance.requestCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("reports a refusal, carrying the reason through unchanged")
        void reportsRefusalWithReason() {
            compliance.alwaysRespondWith(StubComplianceService.Reply.ok(
                    StubComplianceService.rejected("REF-1", "COUNTERPARTY_BLOCKED")));

            ComplianceAssessment assessment = screen();

            assertThat(assessment.outcome()).isEqualTo(ComplianceOutcome.REJECTED);
            assertThat(assessment.reason()).isEqualTo("COUNTERPARTY_BLOCKED");
        }

        @Test
        @DisplayName("does not retry a refusal, which is a definitive answer")
        void doesNotRetryARefusal() {
            compliance.alwaysRespondWith(StubComplianceService.Reply.ok(
                    StubComplianceService.rejected("REF-1", "SINGLE_TRANSFER_LIMIT_EXCEEDED")));

            screen();

            assertThat(compliance.requestCount())
                    .as("a firm no is a successful call and must not be retried")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a refusal does not count against the circuit breaker")
        void refusalDoesNotOpenTheCircuit() {
            // A run of legitimately blocked payments must not stop the service screening valid ones.
            compliance.alwaysRespondWith(StubComplianceService.Reply.ok(
                    StubComplianceService.rejected("REF-1", "COUNTERPARTY_BLOCKED")));

            for (int i = 0; i < 6; i++) {
                screen();
            }

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        }

        @Test
        @DisplayName("sends the reference, both accounts, the amount and the currency")
        void sendsTheExpectedPayload() {
            compliance.alwaysRespondWith(StubComplianceService.Reply.ok(
                    StubComplianceService.approved("REF-1")));

            screen();

            assertThat(compliance.lastRequestBody())
                    .contains("\"reference\":\"REF-1\"")
                    .contains("\"sourceAccountId\":\"ACC-1001\"")
                    .contains("\"destinationAccountId\":\"ACC-2002\"")
                    .contains("\"amount\":250.00")
                    .contains("\"currency\":\"GBP\"");
        }

        @Test
        @DisplayName("forwards the request's correlation id, so both services' logs join up")
        void forwardsTheCorrelationId() {
            compliance.alwaysRespondWith(StubComplianceService.Reply.ok(
                    StubComplianceService.approved("REF-1")));

            ThreadContext.put(CorrelationId.MDC_KEY, "trace-abc-123");
            try {
                screen();
            } finally {
                ThreadContext.remove(CorrelationId.MDC_KEY);
            }

            assertThat(compliance.lastRequestHeader(CorrelationId.HEADER))
                    .as("without this, a screening decision cannot be tied to the transfer that caused it")
                    .isEqualTo("trace-abc-123");
        }

        @Test
        @DisplayName("sends no correlation header when there is no id in scope")
        void omitsTheHeaderWhenNoIdInScope() {
            // Better an absent header than a fabricated id that correlates with nothing.
            compliance.alwaysRespondWith(StubComplianceService.Reply.ok(
                    StubComplianceService.approved("REF-1")));

            screen();

            assertThat(compliance.lastRequestHeader(CorrelationId.HEADER)).isNull();
        }
    }

    @Nested
    @DisplayName("when compliance is failing")
    class Failures {

        @Test
        @DisplayName("retries a transient server error and succeeds on the second attempt")
        void retriesTransientFailure() {
            compliance.respondWith(attempt -> attempt == 1
                    ? StubComplianceService.Reply.status(503)
                    : StubComplianceService.Reply.ok(StubComplianceService.approved("REF-1")));

            ComplianceAssessment assessment = screen();

            assertThat(assessment.outcome())
                    .as("a blip should not surface to the caller at all")
                    .isEqualTo(ComplianceOutcome.APPROVED);
            assertThat(compliance.requestCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("gives up after the configured attempts and reports UNAVAILABLE")
        void givesUpAfterMaxAttempts() {
            compliance.alwaysRespondWith(StubComplianceService.Reply.status(500));

            ComplianceAssessment assessment = screen();

            assertThat(assessment.outcome()).isEqualTo(ComplianceOutcome.UNAVAILABLE);
            assertThat(assessment.reason()).isEqualTo(HttpComplianceGateway.CALL_FAILED);
            assertThat(compliance.requestCount())
                    .as("exactly the configured number of attempts, no more")
                    .isEqualTo(MAX_ATTEMPTS);
        }

        @Test
        @DisplayName("treats a response slower than the read timeout as a failure")
        void treatsSlowResponseAsFailure() {
            // Without a read timeout this call would hang, holding a request thread and never being
            // recorded as a failure - so the circuit could never trip.
            compliance.alwaysRespondWith(StubComplianceService.Reply.slow(
                    StubComplianceService.approved("REF-1"), Duration.ofMillis(900)));

            ComplianceAssessment assessment = screen();

            assertThat(assessment.outcome()).isEqualTo(ComplianceOutcome.UNAVAILABLE);
            assertThat(assessment.reason()).isEqualTo(HttpComplianceGateway.CALL_FAILED);
        }

        @Test
        @DisplayName("reports UNAVAILABLE, never approval, when it cannot get an answer")
        void neverApprovesWithoutAnAnswer() {
            // The fail-closed guarantee. If this ever returned APPROVED the service would be waving
            // payments through during an outage.
            compliance.alwaysRespondWith(StubComplianceService.Reply.status(500));

            assertThat(screen().isApproved()).isFalse();
        }
    }

    @Nested
    @DisplayName("when we send something wrong")
    class OurFault {

        @Test
        @DisplayName("does not retry a 4xx, because it would fail identically")
        void doesNotRetryClientError() {
            compliance.alwaysRespondWith(StubComplianceService.Reply.status(400));

            ComplianceAssessment assessment = screen();

            assertThat(assessment.reason()).isEqualTo(HttpComplianceGateway.CONTRACT_ERROR);
            assertThat(compliance.requestCount())
                    .as("a 400 is our defect; retrying it only adds load")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("never opens the circuit on our own malformed requests")
        void clientErrorDoesNotOpenTheCircuit() {
            // Otherwise a bug in our payload would trip the breaker and stop us calling a service that
            // is perfectly healthy - turning a small defect into an outage.
            compliance.alwaysRespondWith(StubComplianceService.Reply.status(400));

            for (int i = 0; i < 8; i++) {
                screen();
            }

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(compliance.requestCount()).isEqualTo(8);
        }

        @Test
        @DisplayName("refuses to guess at a decision it does not recognise")
        void refusesUnrecognisedDecision() {
            compliance.alwaysRespondWith(StubComplianceService.Reply.ok(
                    StubComplianceService.withDecision("REF-1", "REFER_TO_HUMAN")));

            ComplianceAssessment assessment = screen();

            assertThat(assessment.outcome()).isEqualTo(ComplianceOutcome.UNAVAILABLE);
            assertThat(assessment.reason()).isEqualTo(HttpComplianceGateway.CONTRACT_ERROR);
        }
    }

    @Nested
    @DisplayName("when the circuit opens")
    class CircuitBreaking {

        @Test
        @DisplayName("stops sending requests altogether, rather than merely returning errors faster")
        void stopsSendingRequests() {
            compliance.alwaysRespondWith(StubComplianceService.Reply.status(500));

            // Two failing logical calls reach the minimum sample and a 100% failure rate.
            screen();
            screen();

            assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

            int requestsBeforeOpenCall = compliance.requestCount();
            ComplianceAssessment whileOpen = screen();

            assertThat(whileOpen.outcome()).isEqualTo(ComplianceOutcome.UNAVAILABLE);
            assertThat(whileOpen.reason()).isEqualTo(HttpComplianceGateway.CIRCUIT_OPEN);
            assertThat(compliance.requestCount())
                    .as("an open circuit must send nothing, sparing a struggling dependency")
                    .isEqualTo(requestsBeforeOpenCall);
        }

        @Test
        @DisplayName("counts one failure per request, not one per retry attempt")
        void countsLogicalCallsNotAttempts() {
            compliance.alwaysRespondWith(StubComplianceService.Reply.status(500));

            screen();

            // One logical call made MAX_ATTEMPTS requests, but the breaker should have recorded a
            // single result. With the decorators nested the other way round it would have recorded
            // two, and tripped twice as fast as the configuration implies.
            assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
            assertThat(compliance.requestCount()).isEqualTo(MAX_ATTEMPTS);
        }

        @Test
        @DisplayName("fails fast while open instead of waiting out the retry backoff")
        void failsFastWhileOpen() {
            compliance.alwaysRespondWith(StubComplianceService.Reply.status(500));
            screen();
            screen();

            long startedAt = System.nanoTime();
            screen();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(elapsed)
                    .as("no network call and no backoff sleep should occur")
                    .isLessThan(Duration.ofMillis(100));
        }
    }
}

