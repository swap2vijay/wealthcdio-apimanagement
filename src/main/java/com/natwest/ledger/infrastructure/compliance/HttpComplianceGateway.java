package com.natwest.ledger.infrastructure.compliance;

import com.natwest.ledger.application.ComplianceAssessment;
import com.natwest.ledger.application.ComplianceGateway;
import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.Money;
import com.natwest.ledger.domain.TransactionReference;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Calls the compliance service over HTTP, guarded by a retry and a circuit breaker.
 *
 * <p><b>Why the decorators are composed in code rather than with annotations.</b> Resilience4j's
 * {@code @Retry} and {@code @CircuitBreaker} annotations are applied as AOP aspects whose relative
 * order is decided by configuration properties. Getting that order wrong is silent and consequential:
 * with retry on the outside of the breaker, an open circuit throws
 * {@link CallNotPermittedException} which the retry then dutifully retries, and if the breaker also
 * declares a fallback, the retry sees a successful return and never retries anything at all. Composing
 * the two here makes the nesting a visible, reviewable line of code instead of an emergent property of
 * two YAML keys.
 *
 * <p><b>The chosen order is {@code circuitBreaker(retry(call))}.</b> Retries are an internal detail of
 * one logical request, so the breaker records one result per request rather than one per attempt -
 * otherwise a single request's three failed attempts would count triple and trip the circuit three
 * times faster than the configured threshold suggests. It also means that when the circuit is open the
 * call fails immediately, instead of sleeping through two pointless backoff intervals first.
 *
 * <p><b>Every failure becomes {@code UNAVAILABLE}; none escapes as an exception.</b> An unreachable
 * dependency is an ordinary operating condition in a distributed system, so it is reported as a value.
 * What the service then does about it - refuse the transfer rather than allow it unscreened - is a
 * policy decision that belongs to the orchestrator, not to this adapter.
 */
@Component
public class HttpComplianceGateway implements ComplianceGateway {

    private static final Logger log = LogManager.getLogger(HttpComplianceGateway.class);

    /** The name shared by the circuit breaker and retry configuration in application.yml. */
    static final String INSTANCE_NAME = "compliance";

    private static final String SCREENINGS_PATH = "/api/v1/screenings";

    /** The circuit is open, so no call was attempted. */
    static final String CIRCUIT_OPEN = "CIRCUIT_OPEN";

    /** The call was attempted and failed, or timed out. */
    static final String CALL_FAILED = "SCREENING_CALL_FAILED";

    /** We were understood, but the exchange did not match the expected contract. */
    static final String CONTRACT_ERROR = "SCREENING_CONTRACT_ERROR";

    private static final String DECISION_APPROVED = "APPROVED";
    private static final String DECISION_REJECTED = "REJECTED";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public HttpComplianceGateway(RestClient complianceRestClient,
                                 CircuitBreakerRegistry circuitBreakerRegistry,
                                 RetryRegistry retryRegistry) {
        this.restClient = complianceRestClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
        this.retry = retryRegistry.retry(INSTANCE_NAME);
    }

    @Override
    public ComplianceAssessment screen(TransactionReference reference,
                                       AccountId source,
                                       AccountId destination,
                                       Money amount) {

        Supplier<ComplianceAssessment> call = () -> requestScreening(reference, source, destination, amount);

        // Retry on the inside, breaker on the outside. See the class note for why this order.
        Supplier<ComplianceAssessment> guarded = CircuitBreaker.decorateSupplier(
                circuitBreaker, Retry.decorateSupplier(retry, call));

        try {
            return guarded.get();

        } catch (CallNotPermittedException e) {
            // The circuit is open. Nothing was sent, which is the entire benefit: we are not adding
            // load to a service that is already failing, and the caller finds out immediately.
            log.warn("Compliance circuit is open; transfer {} not screened", reference);
            return ComplianceAssessment.unavailable(reference, CIRCUIT_OPEN);

        } catch (ComplianceContractException e) {
            // Our fault, not theirs. Logged at ERROR because it needs a code fix, not an operator.
            log.error("Compliance screening contract error for transfer {}: {}", reference, e.getMessage());
            return ComplianceAssessment.unavailable(reference, CONTRACT_ERROR);

        } catch (Exception e) {
            log.warn("Compliance screening failed for transfer {} after {} attempt(s): {}",
                    reference, retry.getRetryConfig().getMaxAttempts(), e.getMessage());
            return ComplianceAssessment.unavailable(reference, CALL_FAILED);
        }
    }

    private ComplianceAssessment requestScreening(TransactionReference reference,
                                                 AccountId source,
                                                 AccountId destination,
                                                 Money amount) {
        ScreeningPayloads.Request payload = new ScreeningPayloads.Request(
                reference.value(),
                source.value(),
                destination.value(),
                amount.amount(),
                amount.currency().getCurrencyCode());

        ScreeningPayloads.Response response = restClient.post()
                .uri(SCREENINGS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                // A 4xx means we sent something wrong. Separated from 5xx so it is neither retried
                // nor counted against the circuit breaker.
                .onStatus(HttpStatusCode::is4xxClientError, (request, res) -> {
                    throw new ComplianceContractException(
                            "compliance rejected the screening request with " + res.getStatusCode());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, res) -> {
                    throw new ComplianceCallFailedException(
                            "compliance returned " + res.getStatusCode());
                })
                .body(ScreeningPayloads.Response.class);

        if (response == null || response.decision() == null) {
            throw new ComplianceContractException("compliance returned no decision");
        }

        return toAssessment(reference, response);
    }

    private static ComplianceAssessment toAssessment(TransactionReference reference,
                                                     ScreeningPayloads.Response response) {
        String decision = response.decision().trim().toUpperCase(Locale.ROOT);

        return switch (decision) {
            case DECISION_APPROVED -> ComplianceAssessment.approved(reference);

            // Defend against a rejection arriving without a reason: an assessment insists on one, and
            // a missing reason should not turn a clear refusal into an exception.
            case DECISION_REJECTED -> ComplianceAssessment.rejected(reference,
                    (response.reason() == null || response.reason().isBlank())
                            ? "REJECTED_WITHOUT_REASON"
                            : response.reason());

            // A decision this version has never heard of. Treated as a contract error rather than
            // guessed at, because guessing about a compliance decision is not acceptable.
            default -> throw new ComplianceContractException(
                    "compliance returned an unrecognised decision '%s'".formatted(response.decision()));
        };
    }
}
