package com.natwest.ledger.config;

import com.natwest.ledger.observability.CorrelationId;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/** Builds the HTTP client used to reach the compliance service, with its timeouts applied. */
@Configuration
public class ComplianceClientConfiguration {

    private static final Logger log = LogManager.getLogger(ComplianceClientConfiguration.class);

    /**
     * A {@link RestClient} dedicated to the compliance service.
     *
     * <p>Its own client rather than one shared instance, so the timeouts suited to a fast synchronous
     * rule check cannot be inherited by some future call to a slower dependency. Timeout settings are
     * a property of the conversation, not of the process.
     */
    @Bean
    public RestClient complianceRestClient(ComplianceClientProperties properties) {
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(properties.connectTimeout())
                        .withReadTimeout(properties.readTimeout()));

        log.info("Compliance client configured for {} (connect={}, read={})",
                properties.baseUrl(), properties.connectTimeout(), properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .requestInterceptor(correlationIdPropagatingInterceptor())
                .build();
    }

    /**
     * Forwards this request's correlation id to the compliance service.
     *
     * <p>This is what turns two separate log streams into one traceable conversation. Without it, the
     * ledger service knows a screening call failed and the compliance service knows it refused
     * something, but nothing connects the two - and a support question about one payment becomes an
     * exercise in matching timestamps.
     *
     * <p>Reads from the logging context rather than taking a parameter, so no method signature between
     * here and the request handler has to carry a tracing concern that none of them are about.
     *
     * <p>Sends nothing when no id is in scope, which happens for calls not made on a request thread.
     * A missing header is preferable to a fabricated one that correlates with nothing.
     */
    private static ClientHttpRequestInterceptor correlationIdPropagatingInterceptor() {
        return (request, body, execution) -> {
            CorrelationId.current().ifPresent(id -> request.getHeaders().set(CorrelationId.HEADER, id));
            return execution.execute(request, body);
        };
    }
}

