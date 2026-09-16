package com.natwest.ledger.model;

import com.natwest.ledger.exception.InvalidAccountIdException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Specifies what makes an account identifier valid, and the normalisation that makes
 * "same account" an unambiguous question.
 */
@DisplayName("An account id")
class AccountIdTest {

    @Test
    @DisplayName("keeps a well-formed identifier as given")
    void keepsWellFormedIdentifier() {
        assertThat(AccountId.of("ACC-1001").value()).isEqualTo("ACC-1001");
    }

    @Test
    @DisplayName("normalises case, so 'acc-1' and 'ACC-1' are the same account")
    void normalisesCase() {
        assertThat(AccountId.of("acc-1001")).isEqualTo(AccountId.of("ACC-1001"));
        assertThat(AccountId.of("acc-1001")).hasSameHashCodeAs(AccountId.of("ACC-1001"));
    }

    @Test
    @DisplayName("trims surrounding whitespace, a common artefact of copy-paste")
    void trimsWhitespace() {
        assertThat(AccountId.of("  ACC-1001  ").value()).isEqualTo("ACC-1001");
    }

    @Test
    @DisplayName("accepts digits, letters, dashes and underscores")
    void acceptsSupportedCharacters() {
        assertThat(AccountId.of("GB_82_WEST_1234").value()).isEqualTo("GB_82_WEST_1234");
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @DisplayName("rejects identifiers that are blank or absent")
    @ValueSource(strings = {"", " ", "   ", "\t"})
    void rejectsBlank(String candidate) {
        assertThatThrownBy(() -> AccountId.of(candidate))
                .isInstanceOf(InvalidAccountIdException.class)
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("rejects a null identifier")
    void rejectsNull() {
        assertThatThrownBy(() -> AccountId.of(null))
                .isInstanceOf(InvalidAccountIdException.class);
    }

    @Test
    @DisplayName("rejects an identifier shorter than three characters as too easy to collide")
    void rejectsTooShort() {
        assertThatThrownBy(() -> AccountId.of("AB"))
                .isInstanceOf(InvalidAccountIdException.class)
                .hasMessageContaining("between 3 and 36");
    }

    @Test
    @DisplayName("rejects an identifier longer than the storage column allows")
    void rejectsTooLong() {
        String thirtySeven = "A".repeat(37);

        assertThatThrownBy(() -> AccountId.of(thirtySeven))
                .isInstanceOf(InvalidAccountIdException.class)
                .hasMessageContaining("between 3 and 36");
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @DisplayName("rejects characters that could confuse routing, logs or URLs")
    @ValueSource(strings = {"ACC 1001", "ACC/1001", "ACC.1001", "ACC#1001", "-ACC1001", "_ACC1001", "ACC:1001"})
    void rejectsUnsupportedCharacters(String candidate) {
        assertThatThrownBy(() -> AccountId.of(candidate))
                .isInstanceOf(InvalidAccountIdException.class);
    }

    @Test
    @DisplayName("renders as its bare value, so log lines stay readable")
    void rendersAsBareValue() {
        assertThat(AccountId.of("ACC-1001")).hasToString("ACC-1001");
    }
}

