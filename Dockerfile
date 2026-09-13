# ---- Build stage: full JDK + Maven, discarded after this stage (learning.md #16.1) ----
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Copy just the POM first so Docker can cache the dependency-download layer
# separately from the (much more frequently changing) source compile step.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
# Tests run as part of the image build (they are pure unit tests — no database, ~1s), so
# an image that builds is an image whose auth, fingerprinting and DATABASE_URL parsing
# were verified. The concurrency invariants are verified separately, against a running
# instance, by scripts/burst_test.sh.
RUN mvn -q -B clean package

# ---- Runtime stage: JRE only, non-root user (learning.md #16.2) ----
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S wallet && adduser -S wallet -G wallet

WORKDIR /app
# Left owned by root and world-readable, deliberately — the `wallet` user needs to read
# this jar, not write it, so a compromised process cannot rewrite the code it runs.
#
# Note there is no `RUN chown` here: changing ownership of a 52MB file writes a whole
# second copy of it into a new layer, because layers store changed files in full. That
# single line cost ~54MB of image — more than the jar itself. Where ownership genuinely
# has to change, use `COPY --chown=...`, which sets it as the file is written.
COPY --from=builder /build/target/*.jar app.jar

USER wallet

ENV PORT=8080
EXPOSE 8080

# Free-tier hosts run this in 256-512MB. The JVM's default heap sizing reads the
# container limit, but 25% of 512MB is a needlessly small heap for a service whose
# working set is tiny; SerialGC avoids paying for GC threads we have no cores for.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

# Asks the app itself, via Actuator (learning.md #15.1), not just "is the process alive"
# (learning.md #16.3). Reads $PORT rather than hardcoding 8080, so the healthcheck
# follows the app when a host assigns it a different port.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider "http://localhost:${PORT}/actuator/health" || exit 1

# `exec` so the JVM becomes PID 1 and receives SIGTERM directly — otherwise the shell
# swallows it and the container is SIGKILLed after the grace period, mid-transaction.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
