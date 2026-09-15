package com.natwest.ledger.config;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
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
                .build();
    }
}
