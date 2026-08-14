# Builds and runs the HTTP service from a clean checkout, so the demo is one command.
#
# Multi-stage rather than copying in a jar somebody built by hand: `docker compose up` has to work
# without a Maven step first, or the instruction is really two instructions and the first one is
# undocumented.

# ---------------------------------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-24 AS build
WORKDIR /src

# The poms alone first, then dependencies. Docker caches per layer, so editing a Java file does not
# re-download the world - only a change to a pom does.
COPY pom.xml .
COPY ludus-core/pom.xml   ludus-core/
COPY ludus-eval/pom.xml   ludus-eval/
COPY ludus-search/pom.xml ludus-search/
COPY ludus-nnue/pom.xml   ludus-nnue/
COPY ludus-uci/pom.xml    ludus-uci/
COPY ludus-tools/pom.xml  ludus-tools/
COPY ludus-server/pom.xml ludus-server/
RUN mvn --batch-mode --no-transfer-progress -pl ludus-server -am dependency:go-offline -DskipTests

COPY ludus-core/src   ludus-core/src
COPY ludus-eval/src   ludus-eval/src
COPY ludus-search/src ludus-search/src
COPY ludus-nnue/src   ludus-nnue/src
COPY ludus-server/src ludus-server/src

# Tests are not run here. They run in CI, where a failure is visible and blocks a merge; running them
# again inside an image build only makes `docker compose up` slower and fails for reasons - no Docker
# socket for Testcontainers - that say nothing about the code.
RUN mvn --batch-mode --no-transfer-progress -pl ludus-server -am -DskipTests package \
 && java -Djarmode=tools -jar ludus-server/target/ludus-server-*.jar extract --layers --destination /app

# ---------------------------------------------------------------------------------------------------
# Run
# ---------------------------------------------------------------------------------------------------
FROM eclipse-temurin:24-jre-alpine
WORKDIR /app

# Unprivileged. Nothing here needs root, and a container that runs as root because nobody thought about
# it is the single most common finding in any review of one.
RUN addgroup -S ludus && adduser -S ludus -G ludus
USER ludus

# Copied in the order they change: dependencies rarely, application code every build. Spring Boot's
# layered jars exist for exactly this, and the payoff is a rebuild that pushes kilobytes.
#
# What `extract --layers` actually produces in Boot 3.5 is a plain jar plus a lib/ directory, with the
# jar's manifest carrying a Class-Path that points into it — not the exploded BOOT-INF layout with a
# JarLauncher that older guides describe. So the entry point below is an ordinary `java -jar`. Worth
# writing down: the wrong version of this fails with ClassNotFoundException on a Spring class, which
# reads like a broken dependency rather than a wrong launcher.
COPY --from=build --chown=ludus:ludus /app/dependencies/ ./
COPY --from=build --chown=ludus:ludus /app/spring-boot-loader/ ./
COPY --from=build --chown=ludus:ludus /app/snapshot-dependencies/ ./
COPY --from=build --chown=ludus:ludus /app/application/ ./

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes itself from the container's limit
# instead of the host's memory, which is the difference between "works on my machine" and "gets killed
# by the OOM killer in production". The engine pool sizes itself from availableProcessors(), which
# respects the CPU limit for the same reason.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/ludus-server-*.jar"]
