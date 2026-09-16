package com.natwest.ledger.observability;

import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Specifies the correlation id behaviour a client can actually observe.
 *
 * <p>Runs over HTTP because the guarantees are about the filter's interaction with the request
 * lifecycle: that the header comes back even on an error response, and that the logging context does not
 * leak from one request to the next on a pooled thread.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Correlation ids over HTTP")
class CorrelationIdApiTest {

    private static final String UNKNOWN_ACCOUNT = "/api/v1/accounts/ACC-9999";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("echoes back an id the caller supplied")
    void echoesSuppliedId() throws Exception {
        mockMvc.perform(get(UNKNOWN_ACCOUNT).header(CorrelationId.HEADER, "client-supplied-42"))
                .andExpect(header().string(CorrelationId.HEADER, "client-supplied-42"));
    }

    @Test
    @DisplayName("returns a generated id when the caller supplies none, so every response is traceable")
    void generatesIdWhenAbsent() throws Exception {
        mockMvc.perform(get(UNKNOWN_ACCOUNT))
                .andExpect(header().string(CorrelationId.HEADER, matchesPattern("[A-Za-z0-9._-]{1,64}")));
    }

    @Test
    @DisplayName("returns the id on an error response too, which is when a caller most needs it")
    void returnsIdOnErrorResponse() throws Exception {
        // A caller reporting a failure can quote this header, and the whole request can be found in the
        // log immediately. An id present only on success would be useless for support.
        mockMvc.perform(get(UNKNOWN_ACCOUNT).header(CorrelationId.HEADER, "failing-request-1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(CorrelationId.HEADER, "failing-request-1"));
    }

    @Test
    @DisplayName("refuses an id containing a newline, which could forge log entries")
    void refusesLogForgingAttempt() throws Exception {
        mockMvc.perform(get(UNKNOWN_ACCOUNT)
                        .header(CorrelationId.HEADER, "ok\nINFO  - transfer approved"))
                .andExpect(header().string(CorrelationId.HEADER, not("ok\nINFO  - transfer approved")))
                .andExpect(header().string(CorrelationId.HEADER, matchesPattern("[A-Za-z0-9._-]{1,64}")));
    }

    @Test
    @DisplayName("leaves nothing on the logging context once the request is done")
    void clearsTheLoggingContextAfterwards() throws Exception {
        // Servlet threads are pooled. A leaked id would be stamped onto the next, unrelated request -
        // misleading during debugging and a small privacy leak between customers.
        mockMvc.perform(get(UNKNOWN_ACCOUNT).header(CorrelationId.HEADER, "leak-check-1"));

        assertThat(ThreadContext.get(CorrelationId.MDC_KEY))
                .as("the correlation id must not survive the request that set it")
                .isNull();
    }

    @Test
    @DisplayName("gives consecutive requests different ids when neither supplies one")
    void doesNotReuseGeneratedIds() throws Exception {
        String first = mockMvc.perform(get(UNKNOWN_ACCOUNT))
                .andReturn().getResponse().getHeader(CorrelationId.HEADER);
        String second = mockMvc.perform(get(UNKNOWN_ACCOUNT))
                .andReturn().getResponse().getHeader(CorrelationId.HEADER);

        assertThat(first).isNotBlank().isNotEqualTo(second);
    }
}

