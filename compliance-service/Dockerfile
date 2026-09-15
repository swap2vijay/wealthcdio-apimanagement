# =============================================================================
# compliance-service (Service B)
#
# Build from the compliance-service directory:
#   docker build -t compliance-service:0.1.0 compliance-service
#
# Its own Dockerfile rather than a shared one, because the two services are
# independently deployable. A single build definition would quietly couple them:
# a change needed by one would rebuild and re-release the other.
# =============================================================================

FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

COPY pom.xml ./
RUN mvn -B -e dependency:go-offline

COPY src ./src
RUN mvn -B -e verify

FROM eclipse-temurin:17-jre-jammy AS runtime

RUN groupadd --system --gid 10001 compliance \
 && useradd --system --uid 10001 --gid compliance --no-create-home --shell /usr/sbin/nologin compliance

WORKDIR /app

COPY --from=build --chown=root:root --chmod=444 /build/target/compliance-service-*.jar /app/application.jar

USER 10001:10001

EXPOSE 8081

# Smaller heap headroom than the ledger service: this one holds no database
# connections, no persistence context and no ledger, so its footprint is mostly
# the JVM itself and the web layer.
ENV JAVA_TOOL_OPTIONS="\
-XX:MaxRAMPercentage=70.0 \
-XX:+ExitOnOutOfMemoryError \
-XX:+UseContainerSupport \
-Duser.timezone=UTC"

ENV LOGGING_CONFIG="classpath:log4j2-json.xml"

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
