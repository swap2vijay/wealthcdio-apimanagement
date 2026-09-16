package com.natwest.compliance.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the environment variable names the deployment artefacts use for this service.
 *
 * <p>Same reasoning as the ledger service's copy: the compose file and Helm ConfigMap configure the
 * service entirely through the environment, and nothing else in the build reads those files.
 *
 * <p>The stakes are higher here than for a wrong URL. {@code singleTransferLimit} has a default, so a
 * name that binds to nothing does not fail - it silently screens every transfer against Â£10,000 while
 * the deployment believes some other figure is in force. A limit that quietly ignores its
 * configuration is a compliance incident rather than a bug report, which is reason enough for a test
 * whose only job is to assert that a string matches.
 */
@DisplayName("Environment variable binding (the contract with Docker and Helm)")
class EnvironmentVariableBindingTest {

    private static Binder binderWithEnvironment(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources()
                .replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        return Binder.get(environment);
    }

    private static ComplianceProperties bind(Map<String, Object> variables) {
        return binderWithEnvironment(variables)
                .bind("compliance", Bindable.of(ComplianceProperties.class))
                .get();
    }

    @Test
    @DisplayName("COMPLIANCE_SINGLE_TRANSFER_LIMIT sets the transfer limit")
    void singleTransferLimitBinds() {
        // The underscored spelling of a hyphenated property. Spring's environment mapper tries both
        // this and the hyphen-removed COMPLIANCE_SINGLETRANSFERLIMIT; the readable one is what the
        // deployment files use.
        assertThat(bind(Map.of("COMPLIANCE_SINGLE_TRANSFER_LIMIT", "2500.00")).singleTransferLimit())
                .isEqualByComparingTo(new BigDecimal("2500.00"));
    }

    @Test
    @DisplayName("COMPLIANCE_SINGLETRANSFERLIMIT works too")
    void canonicalSingleTransferLimitBinds() {
        assertThat(bind(Map.of("COMPLIANCE_SINGLETRANSFERLIMIT", "2500.00")).singleTransferLimit())
                .isEqualByComparingTo(new BigDecimal("2500.00"));
    }

    @Test
    @DisplayName("an unrecognised name leaves the default limit in force, without complaint")
    void anUnrecognisedNameBindsNothing() {
        BindResult<ComplianceProperties> result =
                binderWithEnvironment(Map.of("COMPLIANCE_TRANSFER_LIMIT", "2500.00"))
                        .bind("compliance", Bindable.of(ComplianceProperties.class));

        assertThat(result.isBound()).isFalse();
    }

    @Test
    @DisplayName("COMPLIANCE_BLOCKEDACCOUNTS_0, _1 ... build the blocked-account list")
    void blockedAccountsBindFromIndexedVariables() {
        // Indexed suffixes are how a collection is expressed in the environment. Ugly, but it keeps a
        // regulatory list out of the jar. Note the trailing number is a genuine index here, not part
        // of a property name.
        ComplianceProperties properties = bind(Map.of(
                "COMPLIANCE_BLOCKEDACCOUNTS_0", "ACC-BLOCKED",
                "COMPLIANCE_BLOCKEDACCOUNTS_1", "acc-sanctioned"));

        assertThat(properties.blockedAccounts()).containsExactly("ACC-BLOCKED", "acc-sanctioned");
        // Screening compares against the normalised set, so case in configuration does not create a
        // trivially bypassable blocked list.
        assertThat(properties.normalisedBlockedAccounts()).contains("ACC-SANCTIONED");
    }
}

