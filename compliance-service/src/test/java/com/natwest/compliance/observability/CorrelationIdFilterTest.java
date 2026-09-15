package com.natwest.compliance.observability;

import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Specifies that this service adopts the ledger service's correlation id.
 *
 * <p>This is the other half of the cross-service trace. If the id were dropped here, a screening
 * decision could not be tied back to the transfer that requested it, and diagnosing a stopped payment
 * would mean matching two log streams by timestamp - which fails exactly when volume is high enough to
 * matter.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Correlation ids in the compliance service")
class CorrelationIdFilterTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String SCREENING_BODY = """
            {"reference":"REF-1","sourceAccountId":"ACC-1001","destinationAccountId":"ACC-2002",
             "amount":250.00,"currency":"GBP"}
            """;

    @Test
    @DisplayName("adopts and echoes the id supplied by the ledger service")
    void adoptsCallersId() throws Exception {
        mockMvc.perform(post("/api/v1/screenings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SCREENING_BODY)
                        .header(CorrelationIdFilter.HEADER, "ledger-trace-99"))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER, "ledger-trace-99"));
    }

    @Test
    @DisplayName("generates its own when called without one")
    void generatesWhenAbsent() throws Exception {
        mockMvc.perform(post("/api/v1/screenings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SCREENING_BODY))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER,
                        matchesPattern("[A-Za-z0-9._-]{1,64}")));
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @DisplayName("refuses an id that could forge a log entry, even from a trusted caller")
    @ValueSource(strings = {"bad\nINFO forged", "bad\r\nWARN", "has space", "way-too-long-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void refusesUnsafeId(String hostile) throws Exception {
        // The ledger service already sanitises, but this service must not depend on that: it is reachable
        // independently, and a control that assumes its caller is well-behaved is not a control.
        mockMvc.perform(post("/api/v1/screenings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(SCREENING_BODY)
                        .header(CorrelationIdFilter.HEADER, hostile))
                .andExpect(header().string(CorrelationIdFilter.HEADER, not(hostile)))
                .andExpect(header().string(CorrelationIdFilter.HEADER,
                        matchesPattern("[A-Za-z0-9._-]{1,64}")));
    }

    @Test
    @DisplayName("leaves nothing on the logging context after the request")
    void clearsTheLoggingContext() throws Exception {
        mockMvc.perform(post("/api/v1/screenings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(SCREENING_BODY)
                .header(CorrelationIdFilter.HEADER, "leak-check"));

        assertThat(ThreadContext.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("returns the id on a rejected request too")
    void returnsIdOnBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/screenings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(CorrelationIdFilter.HEADER, "invalid-payload-1"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(CorrelationIdFilter.HEADER, "invalid-payload-1"));
    }
}
