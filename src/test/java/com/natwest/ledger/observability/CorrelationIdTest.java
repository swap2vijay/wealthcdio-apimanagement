package com.natwest.ledger.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies how a caller-supplied correlation id is treated.
 *
 * <p>The security-relevant cases are the interesting ones. This value arrives in a request header and is
 * written verbatim into log lines, so a newline in it lets an attacker append fabricated log entries -
 * log forging. These tests pin down that anything unusual is discarded rather than escaped or trusted.
 */
@DisplayName("A correlation id")
class CorrelationIdTest {

    @Test
    @DisplayName("keeps a well-formed id supplied by the caller, so a trace spans systems")
    void keepsWellFormedId() {
        assertThat(CorrelationId.sanitiseOrGenerate("abc-123_XYZ.9")).isEqualTo("abc-123_XYZ.9");
    }

    @Test
    @DisplayName("keeps a UUID, the most likely thing an upstream service will send")
    void keepsUuid() {
        String uuid = "6f1c2b3a-4d5e-6f70-8192-a3b4c5d6e7f8";

        assertThat(CorrelationId.sanitiseOrGenerate(uuid)).isEqualTo(uuid);
    }

    @Test
    @DisplayName("generates one when the caller sends none")
    void generatesWhenAbsent() {
        assertThat(CorrelationId.sanitiseOrGenerate(null)).isNotBlank();
    }

    @Test
    @DisplayName("generates a different id each time, so two requests never collide")
    void generatesDistinctIds() {
        assertThat(CorrelationId.generate()).isNotEqualTo(CorrelationId.generate());
    }

    @Test
    @DisplayName("trims incidental whitespace rather than rejecting the id over it")
    void trimsWhitespace() {
        assertThat(CorrelationId.sanitiseOrGenerate("  abc-123  ")).isEqualTo("abc-123");
    }

    @ParameterizedTest(name = "replaces \"{0}\"")
    @DisplayName("refuses characters that could forge or corrupt a log line")
    @ValueSource(strings = {
            "abc\nINFO forged log entry",
            "abc\r\nWARN injected",
            "abc\u001b[31m",
            "abc def",
            "abc\tdef",
            "<script>alert(1)</script>",
            "abc\u0000def",
            "\"quoted\"",
            "{\"json\":\"injection\"}"
    })
    void refusesUnsafeCharacters(String hostile) {
        String sanitised = CorrelationId.sanitiseOrGenerate(hostile);

        assertThat(sanitised)
                .as("a hostile id must be discarded, not passed through")
                .isNotEqualTo(hostile);
        assertThat(CorrelationId.isAcceptable(sanitised))
                .as("and whatever replaces it must itself be safe to log")
                .isTrue();
    }

    @Test
    @DisplayName("refuses an over-long id, which would bloat every line of the request")
    void refusesOverLongId() {
        String tooLong = "a".repeat(65);

        assertThat(CorrelationId.sanitiseOrGenerate(tooLong)).isNotEqualTo(tooLong);
    }

    @Test
    @DisplayName("accepts an id at exactly the length limit")
    void acceptsIdAtTheLimit() {
        String atLimit = "a".repeat(64);

        assertThat(CorrelationId.sanitiseOrGenerate(atLimit)).isEqualTo(atLimit);
    }

    @Test
    @DisplayName("refuses a blank id, which would correlate nothing")
    void refusesBlank() {
        assertThat(CorrelationId.sanitiseOrGenerate("   ")).isNotBlank();
        assertThat(CorrelationId.sanitiseOrGenerate("")).isNotBlank();
    }

    @Test
    @DisplayName("reports no id in scope outside a request")
    void reportsNoIdOutsideARequest() {
        assertThat(CorrelationId.current()).isEmpty();
    }
}
