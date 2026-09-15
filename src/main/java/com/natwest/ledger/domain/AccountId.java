package com.natwest.ledger.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The unique identifier of an account.
 *
 * <p>A wrapper rather than a bare {@code String} so that an account id can never be passed where
 * a holder name or a narrative was expected - a mistake the compiler cannot catch when every
 * identifier is a {@code String}. It also gives validation a single home.
 *
 * <p><b>Identifiers are normalised to upper case.</b> Left as-is, {@code "acc-1"} and
 * {@code "ACC-1"} would be two different accounts in a map but the same account to a human, which
 * is exactly the sort of ambiguity that turns into a duplicate-account defect. Normalising on the
 * way in means equality is decided once, here, instead of at every comparison site.
 */
public record AccountId(String value) {

    private static final int MIN_LENGTH = 3;
    private static final int MAX_LENGTH = 36;

    /** Alphanumeric, dash and underscore; must start with an alphanumeric. */
    private static final Pattern VALID = Pattern.compile("^[A-Z0-9][A-Z0-9_-]*$");

    public AccountId {
        if (value == null || value.isBlank()) {
            throw new InvalidAccountIdException("An account id is required but was blank", value);
        }
        value = value.trim().toUpperCase(Locale.ROOT);
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new InvalidAccountIdException(
                    "Account id must be between %d and %d characters but was %d"
                            .formatted(MIN_LENGTH, MAX_LENGTH, value.length()),
                    value);
        }
        if (!VALID.matcher(value).matches()) {
            throw new InvalidAccountIdException(
                    "Account id '%s' must start with a letter or digit and contain only letters, digits, '-' or '_'"
                            .formatted(value),
                    value);
        }
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
