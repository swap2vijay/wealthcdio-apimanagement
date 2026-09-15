package com.natwest.ledger.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.natwest.ledger.infrastructure.memory.InMemoryAccountRepository;
import com.natwest.ledger.infrastructure.memory.InMemoryLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Specifies the HTTP contract, exercised through the whole stack.
 *
 * <p>Runs against the real application context - controllers, validation, the exception advice, the
 * services and the in-memory adapters - because the interesting behaviour is emergent. Whether a
 * refused withdrawal returns 422 with {@code LDG-1003} and leaves the balance untouched is a fact
 * about the assembled system, and mocking the service away would assert only that the controller
 * calls a method.
 *
 * <p>Failure cases check the resulting balance as well as the status. An endpoint that returns the
 * right error while having already moved money is far worse than one that simply fails.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("The ledger API")
class LedgerApiTest {

    private static final String ACCOUNTS = "/api/v1/accounts";
    private static final String TRANSFERS = "/api/v1/transfers";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemoryAccountRepository accounts;

    @Autowired
    private InMemoryLedgerRepository ledger;

    @BeforeEach
    void clearStores() {
        // The adapters are context-scoped singletons, so state must be reset between tests rather
        // than relying on each test using unique ids.
        accounts.deleteAll();
        ledger.deleteAll();
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions openAccount(String accountId, String holderName, String openingBalance) throws Exception {
        String body = """
                {"accountId":"%s","holderName":"%s","openingBalance":%s}
                """.formatted(accountId, holderName, openingBalance);

        return mockMvc.perform(post(ACCOUNTS).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void givenAccount(String accountId, String openingBalance) throws Exception {
        openAccount(accountId, "Ada Lovelace", openingBalance).andExpect(status().isCreated());
    }

    private ResultActions deposit(String accountId, String amount) throws Exception {
        return mockMvc.perform(post(ACCOUNTS + "/{id}/deposits", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":%s,"narrative":"Salary"}
                        """.formatted(amount)));
    }

    private ResultActions withdraw(String accountId, String amount) throws Exception {
        return mockMvc.perform(post(ACCOUNTS + "/{id}/withdrawals", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"amount":%s}
                        """.formatted(amount)));
    }

    private ResultActions transfer(String from, String to, String amount, String currency) throws Exception {
        String currencyField = (currency == null) ? "" : ",\"currency\":\"%s\"".formatted(currency);
        return mockMvc.perform(post(TRANSFERS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":%s%s}
                        """.formatted(from, to, amount, currencyField)));
    }

    /** Reads the balance over HTTP and compares it exactly, avoiding JSON-number type ambiguity. */
    private BigDecimal balanceOf(String accountId) throws Exception {
        String json = mockMvc.perform(get(ACCOUNTS + "/{id}/balance", accountId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return new BigDecimal(objectMapper.readTree(json).get("balance").asText());
    }

    // ---------------------------------------------------------------- opening

    @Nested
    @DisplayName("opening an account")
    class OpeningAnAccount {

        @Test
        @DisplayName("returns 201 with the new account and where to find it")
        void opensAccount() throws Exception {
            openAccount("ACC-1001", "Ada Lovelace", "250.00")
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", "/api/v1/accounts/ACC-1001"))
                    .andExpect(jsonPath("$.accountId").value("ACC-1001"))
                    .andExpect(jsonPath("$.holderName").value("Ada Lovelace"))
                    .andExpect(jsonPath("$.currency").value("GBP"));

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("250.00");
        }

        @Test
        @DisplayName("normalises the id to upper case, so 'acc-1001' and 'ACC-1001' are one account")
        void normalisesId() throws Exception {
            openAccount("acc-1001", "Ada Lovelace", "10.00")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.accountId").value("ACC-1001"));
        }

        @Test
        @DisplayName("may be opened empty by omitting the opening balance")
        void opensEmptyWhenBalanceOmitted() throws Exception {
            mockMvc.perform(post(ACCOUNTS).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountId":"ACC-1001","holderName":"Ada Lovelace"}
                                    """))
                    .andExpect(status().isCreated());

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("records an opening balance on the statement, so the ledger explains it")
        void recordsOpeningBalanceOnStatement() throws Exception {
            givenAccount("ACC-1001", "250.00");

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-1001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].type").value("DEPOSIT"))
                    .andExpect(jsonPath("$[0].direction").value("CREDIT"))
                    .andExpect(jsonPath("$[0].narrative").value("Opening balance"));
        }

        @Test
        @DisplayName("refuses a duplicate id with 409 and a code saying why")
        void refusesDuplicate() throws Exception {
            givenAccount("ACC-1001", "10.00");

            openAccount("ACC-1001", "Someone Else", "10.00")
                    .andExpect(status().isConflict())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("LDG-2002"))
                    .andExpect(jsonPath("$.details.accountId").value("ACC-1001"));
        }

        @Test
        @DisplayName("refuses a negative opening balance with 400")
        void refusesNegativeOpeningBalance() throws Exception {
            openAccount("ACC-1001", "Ada Lovelace", "-1.00")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LDG-1001"));

            mockMvc.perform(get(ACCOUNTS + "/{id}", "ACC-1001")).andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("lists every missing field at once, not one per round trip")
        void reportsAllValidationFailuresTogether() throws Exception {
            mockMvc.perform(post(ACCOUNTS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LDG-4001"))
                    .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                    .andExpect(jsonPath("$.fieldErrors[*].field")
                            .value(org.hamcrest.Matchers.containsInAnyOrder("accountId", "holderName")));
        }

        @Test
        @DisplayName("refuses an id containing characters that would confuse a URL")
        void refusesInvalidIdFormat() throws Exception {
            openAccount("ACC/1001", "Ada Lovelace", "10.00")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LDG-1005"));
        }

        @Test
        @DisplayName("refuses a body that is not valid JSON without echoing parser internals")
        void refusesUnreadableBody() throws Exception {
            mockMvc.perform(post(ACCOUNTS).contentType(MediaType.APPLICATION_JSON).content("{not json"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LDG-4001"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("valid JSON")));
        }

        @Test
        @DisplayName("refuses a currency code that is not a real currency")
        void refusesUnknownCurrency() throws Exception {
            mockMvc.perform(post(ACCOUNTS).contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"accountId":"ACC-1001","holderName":"Ada","openingBalance":10.00,"currency":"ZZZ"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LDG-4001"));
        }
    }

    // ---------------------------------------------------------------- queries

    @Nested
    @DisplayName("querying an account")
    class Querying {

        @Test
        @DisplayName("reports the balance with the moment it was read")
        void reportsBalance() throws Exception {
            givenAccount("ACC-1001", "75.25");

            mockMvc.perform(get(ACCOUNTS + "/{id}/balance", "ACC-1001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accountId").value("ACC-1001"))
                    .andExpect(jsonPath("$.currency").value("GBP"))
                    .andExpect(jsonPath("$.asOf").isNotEmpty());
        }

        @Test
        @DisplayName("returns 404 with a code for an account that does not exist")
        void reportsUnknownAccount() throws Exception {
            mockMvc.perform(get(ACCOUNTS + "/{id}/balance", "ACC-9999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("LDG-2001"))
                    .andExpect(jsonPath("$.instance").value("/api/v1/accounts/ACC-9999/balance"));
        }

        @Test
        @DisplayName("returns an empty statement for an account that has never transacted")
        void returnsEmptyStatement() throws Exception {
            givenAccount("ACC-1001", "0");

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-1001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("distinguishes an unknown account from one with no transactions")
        void distinguishesUnknownAccountFromEmptyStatement() throws Exception {
            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-9999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("LDG-2001"));
        }

        @Test
        @DisplayName("returns the statement oldest first, with a running balance on each line")
        void returnsStatementChronologically() throws Exception {
            givenAccount("ACC-1001", "100.00");
            deposit("ACC-1001", "10.00").andExpect(status().isCreated());
            withdraw("ACC-1001", "5.00").andExpect(status().isCreated());

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-1001"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[0].narrative").value("Opening balance"))
                    .andExpect(jsonPath("$[1].type").value("DEPOSIT"))
                    .andExpect(jsonPath("$[2].type").value("WITHDRAWAL"))
                    .andExpect(jsonPath("$[2].direction").value("DEBIT"))
                    .andExpect(jsonPath("$[0].entryId").isNotEmpty())
                    .andExpect(jsonPath("$[0].occurredAt").isNotEmpty());
        }
    }

    // ---------------------------------------------------------------- deposits

    @Nested
    @DisplayName("depositing")
    class Depositing {

        @Test
        @DisplayName("returns 201 and the new balance")
        void deposits() throws Exception {
            givenAccount("ACC-1001", "100.00");

            deposit("ACC-1001", "50.00")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("DEPOSIT"))
                    .andExpect(jsonPath("$.direction").value("CREDIT"))
                    .andExpect(jsonPath("$.narrative").value("Salary"))
                    .andExpect(jsonPath("$.reference").isNotEmpty());

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("150.00");
        }

        @Test
        @DisplayName("refuses a zero deposit, which would record a movement that never happened")
        void refusesZero() throws Exception {
            givenAccount("ACC-1001", "100.00");

            deposit("ACC-1001", "0")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LDG-1001"));

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("refuses sub-penny precision rather than silently rounding the instruction")
        void refusesSubPennyPrecision() throws Exception {
            givenAccount("ACC-1001", "100.00");

            deposit("ACC-1001", "10.005")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LDG-1001"));

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("refuses a missing amount and names the field")
        void refusesMissingAmount() throws Exception {
            givenAccount("ACC-1001", "100.00");

            mockMvc.perform(post(ACCOUNTS + "/{id}/deposits", "ACC-1001")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LDG-4001"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"));
        }

        @Test
        @DisplayName("returns 404 when depositing into an account that does not exist")
        void refusesUnknownAccount() throws Exception {
            deposit("ACC-9999", "50.00")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("LDG-2001"));
        }
    }

    // ------------------------------------------------------------ withdrawals

    @Nested
    @DisplayName("withdrawing")
    class Withdrawing {

        @Test
        @DisplayName("returns 201 and reduces the balance")
        void withdraws() throws Exception {
            givenAccount("ACC-1001", "100.00");

            withdraw("ACC-1001", "30.00")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.type").value("WITHDRAWAL"))
                    .andExpect(jsonPath("$.direction").value("DEBIT"));

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("70.00");
        }

        @Test
        @DisplayName("allows an account to be emptied exactly")
        void allowsExactEmptying() throws Exception {
            givenAccount("ACC-1001", "100.00");

            withdraw("ACC-1001", "100.00").andExpect(status().isCreated());

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("returns 422 with the shortfall when the balance cannot cover it")
        void refusesOverdraft() throws Exception {
            givenAccount("ACC-1001", "100.00");

            withdraw("ACC-1001", "130.00")
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("LDG-1003"))
                    .andExpect(jsonPath("$.details.balance").value("100.00"))
                    .andExpect(jsonPath("$.details.requested").value("130.00"))
                    .andExpect(jsonPath("$.details.shortfall").value("30.00"));

            assertThat(balanceOf("ACC-1001"))
                    .as("a refused withdrawal must not move money")
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("refuses to overdraw by even one penny")
        void refusesOverdraftByOnePenny() throws Exception {
            givenAccount("ACC-1001", "100.00");

            withdraw("ACC-1001", "100.01").andExpect(status().isUnprocessableEntity());

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("100.00");
        }
    }

    // -------------------------------------------------------------- transfers

    @Nested
    @DisplayName("transferring")
    class Transferring {

        @Test
        @DisplayName("returns 201 with both legs under one reference")
        void transfers() throws Exception {
            givenAccount("ACC-1001", "100.00");
            givenAccount("ACC-2002", "20.00");

            String json = transfer("ACC-1001", "ACC-2002", "30.00", null)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sourceAccountId").value("ACC-1001"))
                    .andExpect(jsonPath("$.destinationAccountId").value("ACC-2002"))
                    .andExpect(jsonPath("$.debit.type").value("TRANSFER_OUT"))
                    .andExpect(jsonPath("$.credit.type").value("TRANSFER_IN"))
                    .andReturn().getResponse().getContentAsString();

            var tree = objectMapper.readTree(json);
            assertThat(tree.get("debit").get("reference").asText())
                    .as("both legs must share the transfer reference")
                    .isEqualTo(tree.get("credit").get("reference").asText())
                    .isEqualTo(tree.get("reference").asText());

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("70.00");
            assertThat(balanceOf("ACC-2002")).isEqualByComparingTo("50.00");
        }

        @Test
        @DisplayName("conserves money across both accounts")
        void conservesMoney() throws Exception {
            givenAccount("ACC-1001", "100.00");
            givenAccount("ACC-2002", "20.00");

            transfer("ACC-1001", "ACC-2002", "30.00", null).andExpect(status().isCreated());

            assertThat(balanceOf("ACC-1001").add(balanceOf("ACC-2002")))
                    .isEqualByComparingTo("120.00");
        }

        @Test
        @DisplayName("refuses a transfer to the same account with 400")
        void refusesSameAccount() throws Exception {
            givenAccount("ACC-1001", "100.00");

            transfer("ACC-1001", "ACC-1001", "10.00", null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LDG-1004"));

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("moves nothing when the source cannot cover the amount")
        void movesNothingWhenFundsShort() throws Exception {
            givenAccount("ACC-1001", "10.00");
            givenAccount("ACC-2002", "20.00");

            transfer("ACC-1001", "ACC-2002", "50.00", null)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("LDG-1003"));

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("10.00");
            assertThat(balanceOf("ACC-2002"))
                    .as("the destination must never be credited for a transfer that failed")
                    .isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("does not debit the source when the destination does not exist")
        void doesNotDebitWhenDestinationMissing() throws Exception {
            givenAccount("ACC-1001", "100.00");

            transfer("ACC-1001", "ACC-9999", "10.00", null)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("LDG-2001"));

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("refuses an amount in a currency the accounts do not hold")
        void refusesForeignCurrency() throws Exception {
            givenAccount("ACC-1001", "100.00");
            givenAccount("ACC-2002", "0");

            transfer("ACC-1001", "ACC-2002", "10.00", "USD")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LDG-1002"))
                    .andExpect(jsonPath("$.details.expectedCurrency").value("GBP"))
                    .andExpect(jsonPath("$.details.actualCurrency").value("USD"));

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("writes one leg onto each account's statement")
        void writesOneLegPerStatement() throws Exception {
            givenAccount("ACC-1001", "100.00");
            givenAccount("ACC-2002", "0");

            transfer("ACC-1001", "ACC-2002", "30.00", null).andExpect(status().isCreated());

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-2002"))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].type").value("TRANSFER_IN"))
                    .andExpect(jsonPath("$[0].narrative").value("Transfer from ACC-1001"));

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-1001"))
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[1].type").value("TRANSFER_OUT"))
                    .andExpect(jsonPath("$[1].narrative").value("Transfer to ACC-2002"));
        }
    }

    // ----------------------------------------------------------- error format

    @Test
    @DisplayName("returns every failure as RFC 7807 problem+json with a stable code and a timestamp")
    void errorsFollowProblemDetail() throws Exception {
        mockMvc.perform(get(ACCOUNTS + "/{id}", "ACC-9999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("https://api.natwest.example/problems/account-not-found"))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/accounts/ACC-9999"))
                .andExpect(jsonPath("$.code").value("LDG-2001"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
