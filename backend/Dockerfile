# syntax=docker/dockerfile:1.7
#
# ONE Dockerfile for all six Spring Boot services. Which service you get is chosen with a build argument:
#
#   docker build -f backend/Dockerfile --build-arg MODULE=pricing-service --build-arg PORT=8083 -t ridex/pricing:dev backend
#
# docker compose passes the arguments for you (infrastructure/docker-compose/docker-compose.yml). Read docs/docker-concepts.md for the
# ideas behind every line.
#
# MULTI-STAGE BUILD. Three stages, and only the last one becomes the image you run:
#
#   build    full JDK + Maven: compiles everything. Big (about 800 MB) and full of tools an attacker would love. Thrown away.
#   layers   unpacks the finished jar into folders ordered by how often they change.
#   runtime  a small JRE + those folders. This is the image.

# ---------------------------------------------------------------------------------------------------------------------------
# Stage 1: build
# ---------------------------------------------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /workspace

# Keep Maven's memory modest: Docker Desktop on a laptop often has only 4 GB for everything
ENV MAVEN_OPTS="-Xmx768m"

# The whole backend is copied (see .dockerignore: no target/, no data/). The services depend on the shared "platform" module, so
# the reactor builds it first.
COPY . .

# --mount=type=cache keeps ~/.m2 BETWEEN builds on this machine, without storing it in any image layer: the first build downloads the
# internet, later builds only compile. Tests are skipped here on purpose: they run in the test pipeline (they need Docker themselves).
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests -pl '!architecture-tests' package

# ---------------------------------------------------------------------------------------------------------------------------
# Stage 2: layers
# ---------------------------------------------------------------------------------------------------------------------------
FROM build AS layers
ARG MODULE
# Spring Boot can split its jar into layers: libraries (change rarely) / our code (changes every build). Docker caches image layers,
# so after a code change only the small "application" layer is rebuilt and re-pushed; the 40 MB of libraries are reused.
RUN java -Djarmode=tools -jar /workspace/${MODULE}/target/${MODULE}-*.jar extract --layers --destination /extracted \
 && mv /extracted/application/${MODULE}-*.jar /extracted/application/app.jar

# ---------------------------------------------------------------------------------------------------------------------------
# Stage 3: runtime (this is the image)
# ---------------------------------------------------------------------------------------------------------------------------
# JRE only (no compiler, no Maven), on Alpine Linux (a 5 MB base). Pinned to a major version, never "latest".
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG PORT=8080

# A NON-ROOT user. If someone breaks into the application, they are an unprivileged user in a read-only world, not root.
RUN addgroup -S -g 10001 ridex \
 && adduser -S -u 10001 -G ridex -h /app -s /sbin/nologin ridex \
 && mkdir -p /app/data \
 && chown -R ridex:ridex /app
WORKDIR /app

# Least-changing layers first, most-changing last. Order matters for the build cache.
COPY --from=layers --chown=ridex:ridex /extracted/dependencies/ ./
COPY --from=layers --chown=ridex:ridex /extracted/spring-boot-loader/ ./
COPY --from=layers --chown=ridex:ridex /extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=ridex:ridex /extracted/application/ ./

USER ridex

# JVM flags for a container: size the heap as a percentage of the container's memory limit (not of the host's RAM), a simple
# garbage collector and small thread stacks to keep the footprint down. Override with -e JAVA_OPTS=... when running.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:MaxMetaspaceSize=160m -Duser.timezone=UTC"

EXPOSE ${PORT}

# "exec" makes java process number 1, so it receives the stop signal (SIGTERM) from Docker and shuts down cleanly
# (finishes requests, commits Kafka offsets) instead of being killed after 10 seconds.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
