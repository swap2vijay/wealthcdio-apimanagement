# =============================================================================
# ledger-service
#
# Multi-stage: the build tooling never reaches the runtime image. A JDK, Maven,
# the source and the whole dependency cache are large and are an attack surface
# made of software the running service has no use for.
#
# Build from the repository root:
#   docker build -t ledger-service:0.1.0 .
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: build
# -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# Dependencies are resolved before the source is copied, so editing a Java file
# does not invalidate the cached dependency layer. This is the single biggest
# win available in a Java Dockerfile: the layer above changes on every commit,
# the one below changes only when the pom does.
COPY pom.xml ./
RUN mvn -B -e dependency:go-offline

COPY src ./src

# Tests run here, in the image build, so an image cannot be produced from code
# that does not pass them. The CI pipeline also runs them, deliberately: the
# pipeline gives fast feedback and readable reports, this makes the guarantee
# hold even for an image built by hand.
RUN mvn -B -e verify

# -----------------------------------------------------------------------------
# Stage 2: runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

# A JRE, not a JDK: no compiler, no jshell, no attach tooling in production.
#
# Tags rather than digests, for readability. A real deployment should pin
# digests (FROM eclipse-temurin:17-jre-jammy@sha256:...) so that a rebuilt tag
# cannot silently change the base image underneath a release.

# Runs as a non-root user with no shell and no home directory. If the process is
# ever compromised, the attacker inherits an account that can do almost nothing -
# and cannot write to the application directory to persist anything.
RUN groupadd --system --gid 10001 ledger \
 && useradd --system --uid 10001 --gid ledger --no-create-home --shell /usr/sbin/nologin ledger

WORKDIR /app

COPY --from=build --chown=root:root --chmod=444 /build/target/ledger-service-*.jar /app/application.jar

# Owned by root and read-only to the running user: the service has no business
# rewriting its own code, and this is what makes readOnlyRootFilesystem viable
# in the Kubernetes deployment.

USER 10001:10001

EXPOSE 8080

# MaxRAMPercentage, not -Xmx. A fixed heap is either wasteful or fatal the moment
# the container's memory limit changes, whereas this tracks whatever cgroup limit
# Kubernetes applies. 75% leaves room for metaspace, thread stacks, direct
# buffers and the JVM itself - the heap is not the whole of a Java process's
# memory, and forgetting that is the usual cause of a container being OOM-killed
# while the heap looks healthy.
#
# ExitOnOutOfMemoryError is deliberate: a JVM that has exhausted its heap cannot
# be trusted to process a payment correctly. Better to die and let Kubernetes
# replace the pod than to keep serving in an unknown state.
ENV JAVA_TOOL_OPTIONS="\
-XX:MaxRAMPercentage=75.0 \
-XX:InitialRAMPercentage=50.0 \
-XX:+ExitOnOutOfMemoryError \
-XX:+UseContainerSupport \
-Djava.security.egd=file:/dev/./urandom \
-Duser.timezone=UTC"

# Structured logging to stdout. In a container the log is a stream the platform
# collects; a file inside the container would fill an ephemeral layer and hide
# the logs from the system meant to gather them.
ENV LOGGING_CONFIG="classpath:log4j2-json.xml"

# No HEALTHCHECK instruction, on purpose. Kubernetes ignores it entirely and uses
# the probes defined in the Helm chart, and adding curl or wget purely to satisfy
# an instruction the orchestrator disregards would put a network tool into a
# banking image for no benefit. Local compose runs rely on the actuator endpoints
# directly.

# exec form, so the JVM is PID 1 and receives SIGTERM directly. Wrapped in a
# shell it would not, and Kubernetes would wait out the full termination grace
# period before killing it - turning every rolling deploy into a stall.
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
