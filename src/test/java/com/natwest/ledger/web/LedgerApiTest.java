package com.natwest.ledger.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.natwest.ledger.application.ProgrammableComplianceGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p>Runs against the real application context on the default profile - controllers, validation, the
 * exception advice, the services, JPA and H2 - because the interesting behaviour is emergent. Whether
 * a refused withdrawal returns 422 with {@code LDG-1003} and leaves the balance untouched is a fact
 * about the assembled system, and mocking the service away would assert only that the controller
 * calls a method.
 *
 * <p>Deliberately not {@code @Transactional}. Wrapping each test in a rolled-back transaction would
 * be tidier, but it would also mean nothing is ever committed - and commit is exactly when JPA
 * flushes, applies constraints and performs its optimistic version check. Truncating tables between
 * tests instead keeps every write a real one.
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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProgrammableComplianceGateway compliance;

    /**
     * Stands in for the compliance service.
     *
     * <p>The real gateway would try to reach another process, which would make these tests depend on
     * something being started alongside them. Replacing the port keeps the whole ledger stack real -
     * controllers, advice, saga, JPA - while letting each test choose what compliance says. The
     * gateway's own HTTP behaviour is covered by its unit tests against a stub socket.
     */
    @TestConfiguration
    static class ComplianceStubConfiguration {

        @Bean
        @Primary
        ProgrammableComplianceGateway programmableComplianceGateway() {
            return new ProgrammableComplianceGateway();
        }
    }

    @BeforeEach
    void clearTables() {
        // Truncating rather than reaching for the repositories keeps this test independent of which
        // persistence adapter is wired in, and avoids widening any production class's visibility
        // purely for the benefit of a test.
        jdbcTemplate.execute("DELETE FROM ledger_entry");
        jdbcTemplate.execute("DELETE FROM account");
        compliance.reset();
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
                    .andExpect(jsonPath("$.transactions.length()").value(1))
                    .andExpect(jsonPath("$.transactions[0].type").value("DEPOSIT"))
                    .andExpect(jsonPath("$.transactions[0].direction").value("CREDIT"))
                    .andExpect(jsonPath("$.transactions[0].narrative").value("Opening balance"));
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
                    .andExpect(jsonPath("$.transactions.length()").value(0))
                    .andExpect(jsonPath("$.totalTransactions").value(0))
                    .andExpect(jsonPath("$.hasNext").value(false));
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
                    .andExpect(jsonPath("$.accountId").value("ACC-1001"))
                    .andExpect(jsonPath("$.transactions.length()").value(3))
                    .andExpect(jsonPath("$.transactions[0].narrative").value("Opening balance"))
                    .andExpect(jsonPath("$.transactions[1].type").value("DEPOSIT"))
                    .andExpect(jsonPath("$.transactions[2].type").value("WITHDRAWAL"))
                    .andExpect(jsonPath("$.transactions[2].direction").value("DEBIT"))
                    .andExpect(jsonPath("$.transactions[0].entryId").isNotEmpty())
                    .andExpect(jsonPath("$.transactions[0].occurredAt").isNotEmpty());
        }

        @Test
        @DisplayName("pages the statement, reporting the total and whether more remains")
        void pagesTheStatement() throws Exception {
            givenAccount("ACC-1001", "100.00");
            for (int i = 0; i < 4; i++) {
                deposit("ACC-1001", "1.00").andExpect(status().isCreated());
            }
            // 5 entries in total: the opening balance plus four deposits.

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-1001").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.page").value(0))
                    .andExpect(jsonPath("$.size").value(2))
                    .andExpect(jsonPath("$.transactions.length()").value(2))
                    .andExpect(jsonPath("$.totalTransactions").value(5))
                    .andExpect(jsonPath("$.totalPages").value(3))
                    .andExpect(jsonPath("$.hasNext").value(true))
                    .andExpect(jsonPath("$.transactions[0].narrative").value("Opening balance"));

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-1001")
                            .param("page", "2").param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(1))
                    .andExpect(jsonPath("$.hasNext")
                            .value(false));
        }

        @Test
        @DisplayName("keeps the statement in a stable order even when timestamps tie")
        void keepsStableOrderWhenTimestampsTie() throws Exception {
            givenAccount("ACC-1001", "0");
            for (int i = 1; i <= 6; i++) {
                deposit("ACC-1001", i + ".00").andExpect(status().isCreated());
            }

            // Entries written within the same clock tick share an instant. Ordering by timestamp alone
            // would leave their relative order to the database, so the running balance could appear to
            // move backwards. The insertion sequence guarantees it does not.
            String json = mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-1001"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            var transactions = objectMapper.readTree(json).get("transactions");
            BigDecimal previous = BigDecimal.ZERO;
            for (var transaction : transactions) {
                BigDecimal balanceAfter = new BigDecimal(transaction.get("balanceAfter").asText());
                assertThat(balanceAfter)
                        .as("the running balance must increase monotonically across deposits")
                        .isGreaterThan(previous);
                previous = balanceAfter;
            }
            assertThat(previous).isEqualByComparingTo("21.00");
        }

        @Test
        @DisplayName("returns an empty page past the end rather than treating it as an error")
        void returnsEmptyPagePastTheEnd() throws Exception {
            givenAccount("ACC-1001", "100.00");

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-1001")
                            .param("page", "40").param("size", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactions.length()").value(0))
                    .andExpect(jsonPath("$.totalTransactions").value(1));
        }

        @Test
        @DisplayName("caps an over-large page size instead of letting the caller choose the allocation")
        void capsPageSize() throws Exception {
            givenAccount("ACC-1001", "100.00");

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-1001")
                            .param("size", "1000000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.size").value(200));
        }

        @Test
        @DisplayName("rejects a negative page as a caller mistake")
        void rejectsNegativePage() throws Exception {
            givenAccount("ACC-1001", "100.00");

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-1001").param("page", "-1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("LDG-4001"));
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
                    .andExpect(jsonPath("$.transactions.length()").value(1))
                    .andExpect(jsonPath("$.transactions[0].type").value("TRANSFER_IN"))
                    .andExpect(jsonPath("$.transactions[0].narrative").value("Transfer from ACC-1001"));

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-1001"))
                    .andExpect(jsonPath("$.transactions.length()").value(2))
                    .andExpect(jsonPath("$.transactions[1].type").value("TRANSFER_OUT"))
                    .andExpect(jsonPath("$.transactions[1].narrative").value("Transfer to ACC-2002"));
        }

        @Test
        @DisplayName("screens every transfer before completing it")
        void screensEveryTransfer() throws Exception {
            givenAccount("ACC-1001", "100.00");
            givenAccount("ACC-2002", "0");

            transfer("ACC-1001", "ACC-2002", "30.00", null).andExpect(status().isCreated());

            assertThat(compliance.callCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 422 and reverses the debit when compliance refuses")
        void reversesWhenComplianceRefuses() throws Exception {
            givenAccount("ACC-1001", "100.00");
            givenAccount("ACC-2002", "20.00");
            compliance.reject("COUNTERPARTY_BLOCKED");

            transfer("ACC-1001", "ACC-2002", "30.00", null)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("LDG-3001"))
                    .andExpect(jsonPath("$.details.reason").value("COUNTERPARTY_BLOCKED"))
                    .andExpect(jsonPath("$.details.retryable").value(false));

            assertThat(balanceOf("ACC-1001"))
                    .as("a refused transfer must leave the payer exactly as they were")
                    .isEqualByComparingTo("100.00");
            assertThat(balanceOf("ACC-2002")).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("shows the debit and its reversal on the statement, not a silent no-op")
        void showsTheReversalOnTheStatement() throws Exception {
            givenAccount("ACC-1001", "100.00");
            givenAccount("ACC-2002", "0");
            compliance.reject("SINGLE_TRANSFER_LIMIT_EXCEEDED");

            transfer("ACC-1001", "ACC-2002", "30.00", null)
                    .andExpect(status().isUnprocessableEntity());

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-1001"))
                    .andExpect(jsonPath("$.transactions.length()").value(3))
                    .andExpect(jsonPath("$.transactions[1].type").value("TRANSFER_OUT"))
                    .andExpect(jsonPath("$.transactions[2].type").value("TRANSFER_REVERSAL"))
                    .andExpect(jsonPath("$.transactions[2].direction").value("CREDIT"))
                    .andExpect(jsonPath("$.transactions[2].narrative")
                            .value(org.hamcrest.Matchers.containsString("SINGLE_TRANSFER_LIMIT_EXCEEDED")));

            mockMvc.perform(get(ACCOUNTS + "/{id}/transactions", "ACC-2002"))
                    .andExpect(jsonPath("$.transactions.length()").value(0));
        }

        @Test
        @DisplayName("returns 503 and reverses the debit when compliance cannot be reached")
        void failsClosedWhenComplianceIsUnavailable() throws Exception {
            givenAccount("ACC-1001", "100.00");
            givenAccount("ACC-2002", "20.00");
            compliance.beUnavailable("CIRCUIT_OPEN");

            // Fails closed: an outage in a control must not silently switch the control off.
            transfer("ACC-1001", "ACC-2002", "30.00", null)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("LDG-3002"))
                    .andExpect(jsonPath("$.details.retryable").value(true));

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("100.00");
            assertThat(balanceOf("ACC-2002")).isEqualByComparingTo("20.00");
        }

        @Test
        @DisplayName("keeps deposits and withdrawals working while compliance is down")
        void otherOperationsSurviveAComplianceOutage() throws Exception {
            // Only transfers need screening, so an outage there must not take the whole service down.
            givenAccount("ACC-1001", "100.00");
            compliance.beUnavailable("CIRCUIT_OPEN");

            deposit("ACC-1001", "50.00").andExpect(status().isCreated());
            withdraw("ACC-1001", "20.00").andExpect(status().isCreated());

            assertThat(balanceOf("ACC-1001")).isEqualByComparingTo("130.00");
        }

        @Test
        @DisplayName("does not ask compliance about a transfer that could never happen")
        void doesNotScreenAnImpossibleTransfer() throws Exception {
            givenAccount("ACC-1001", "10.00");
            givenAccount("ACC-2002", "0");

            transfer("ACC-1001", "ACC-2002", "50.00", null)
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("LDG-1003"));

            assertThat(compliance.callCount())
                    .as("screening money that is not there wastes a call on the dependency")
                    .isZero();
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
