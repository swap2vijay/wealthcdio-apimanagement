package com.natwest.ledger.observability;

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.ConfigurationSource;
import org.apache.logging.log4j.core.config.xml.XmlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies that both logging configurations are valid and contain what they claim to.
 *
 * <p>The default configuration is exercised by every other test simply by the application starting. The
 * JSON one is not: it is only selected in containers, so a typo in it would sail through the entire
 * build and fail on first deployment - or, worse, fall back to a default configuration and quietly
 * produce unparseable logs that the shipper drops.
 *
 * <p>Parsed into an isolated {@link LoggerContext} rather than by reconfiguring the running one, so this
 * test cannot change how the rest of the suite logs.
 */
@DisplayName("The log4j2 configuration")
class Log4j2ConfigurationTest {

    private static XmlConfiguration parse(String resource) throws Exception {
        try (InputStream stream = Log4j2ConfigurationTest.class.getResourceAsStream("/" + resource)) {
            assertThat(stream).as("%s must be on the classpath", resource).isNotNull();

            XmlConfiguration configuration = new XmlConfiguration(
                    new LoggerContext("isolated-" + resource),
                    new ConfigurationSource(stream));
            configuration.initialize();
            return configuration;
        }
    }

    @Test
    @DisplayName("writes to the console and to a rolling file by default")
    void defaultConfigurationHasConsoleAndRollingFile() throws Exception {
        XmlConfiguration configuration = parse("log4j2-spring.xml");

        assertThat(configuration.getAppenders()).containsKeys("Console", "RollingFile");
        assertThat(configuration.getRootLogger().getAppenderRefs())
                .extracting(ref -> ref.getRef())
                .containsExactlyInAnyOrder("Console", "RollingFile");
    }

    @Test
    @DisplayName("puts the correlation id on every line")
    void defaultPatternIncludesTheTracingKeys() throws Exception {
        // Asserted on the raw text because the value is the pattern itself: a config that parses but
        // omits %X{correlationId} produces logs that cannot be correlated, which is a silent failure.
        String xml = new String(
                getClass().getResourceAsStream("/log4j2-spring.xml").readAllBytes());

        assertThat(xml).contains("%X{correlationId}");
    }

    @Test
    @DisplayName("bounds log retention, so logs cannot fill the disk and stop the service")
    void defaultConfigurationBoundsRetention() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/log4j2-spring.xml").readAllBytes());

        assertThat(xml).contains("DefaultRolloverStrategy");
        assertThat(xml).contains("IfLastModified");
    }

    @Test
    @DisplayName("is parseable in its JSON form, which no other test would exercise")
    void jsonConfigurationIsValid() throws Exception {
        XmlConfiguration configuration = parse("log4j2-json.xml");

        assertThat(configuration.getAppenders()).containsKey("Json");
        assertThat(configuration.getRootLogger().getAppenderRefs())
                .extracting(ref -> ref.getRef())
                .containsExactly("Json");
    }

    @Test
    @DisplayName("writes JSON only to stdout, since a container log is a stream not a file")
    void jsonConfigurationWritesOnlyToStdout() throws Exception {
        XmlConfiguration configuration = parse("log4j2-json.xml");

        // A file appender inside a container fills an ephemeral layer and hides the logs from the
        // platform that is meant to collect them.
        assertThat(configuration.getAppenders()).hasSize(1);
    }

    @Test
    @DisplayName("references a JSON template that is actually on the classpath")
    void jsonTemplateResolves() {
        // The config names classpath:EcsLayout.json, which ships inside log4j-layout-template-json. If
        // that dependency were dropped the XML would still parse, and the failure would surface only on
        // container startup - so the resource is asserted directly.
        assertThat(getClass().getResourceAsStream("/EcsLayout.json"))
                .as("EcsLayout.json must be on the classpath for the JSON layout to initialise")
                .isNotNull();
    }
}

