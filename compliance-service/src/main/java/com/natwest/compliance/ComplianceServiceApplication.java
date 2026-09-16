package com.natwest.compliance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Service B: screens proposed transfers before the ledger service commits them.
 *
 * <p>Deliberately tiny and stateless. It owns one decision - may this transfer proceed - and holds
 * no balances, no ledger and no database. Keeping it that small is what makes it a genuine second
 * service rather than a second copy of the first.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ComplianceServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplianceServiceApplication.class, args);
    }
}

