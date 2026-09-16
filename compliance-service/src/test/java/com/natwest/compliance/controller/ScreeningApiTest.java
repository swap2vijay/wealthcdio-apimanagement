package com.natwest.compliance.controller;

import com.natwest.compliance.model.Decision;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Specifies the HTTP contract the ledger service depends on.
 *
 * <p>The most important assertion here is that a rejection is {@code 200 OK} with
 * {@code decision: REJECTED}. The ledger service's circuit breaker treats HTTP failures as evidence
 * that this service is unwell; if a firm "no" arrived as a 4xx or 5xx, a run of legitimately blocked
 * payments would trip the breaker and stop screening working payments too.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("The screening API")
class ScreeningApiTest {

    @Autowired
    private MockMvc mockMvc;

    private ResultActions screen(String source, String destination, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/screenings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"reference":"REF-1","sourceAccountId":"%s","destinationAccountId":"%s",
                         "amount":%s,"currency":"GBP"}
                        """.formatted(source, destination, amount)));
    }

    @Test
    @DisplayName("approves an ordinary transfer, echoing the reference back")
    void approvesOrdinaryTransfer() throws Exception {
        screen("ACC-1001", "ACC-2002", "250.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("REF-1"))
                .andExpect(jsonPath("$.decision").value("APPROVED"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.screenedAt").isNotEmpty());
    }

    @Test
    @DisplayName("returns 200 with REJECTED for a blocked counterparty, not an HTTP error")
    void rejectionIsASuccessfulCall() throws Exception {
        // A firm "no" must not look like a transport failure, or it would trip the caller's breaker.
        screen("ACC-1001", "ACC-BLOCKED", "10.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("COUNTERPARTY_BLOCKED"));
    }

    @Test
    @DisplayName("returns 200 with REJECTED when the value limit is reached")
    void rejectsAtTheLimit() throws Exception {
        screen("ACC-1001", "ACC-2002", "10000.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("SINGLE_TRANSFER_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("rejects a malformed request with 400, which is a different thing entirely")
    void rejectsMalformedRequest() throws Exception {
        mockMvc.perform(post("/api/v1/screenings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("requires a reference, so a decision can always be traced back to a payment")
    void requiresReference() throws Exception {
        mockMvc.perform(post("/api/v1/screenings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccountId":"ACC-1001","destinationAccountId":"ACC-2002",
                                 "amount":10.00,"currency":"GBP"}
                                """))
                .andExpect(status().isBadRequest());
    }
}

