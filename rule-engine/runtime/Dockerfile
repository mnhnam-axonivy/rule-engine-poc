# syntax=docker/dockerfile:1
#
# Axon Ivy Rules Engine - decision runtime
# One generic image for all customers: DMN models are loaded at runtime through the API, never built into the image.
# The image contains the rule engine and its embedded database. Mount a volume at /data to keep the versions.
#
# Built from this folder; it needs nothing else of the repository. In the root of the repository:
#
#   docker build -t axonivy/rule-engine-runtime:dev rule-engine/runtime
#   docker compose build                     (the same, from docker-compose.yml)
#
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml pom.xml
COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="Axon Ivy Rules Engine" \
      org.opencontainers.image.description="Decision runtime that loads DMN models at runtime. Based on Kogito and Drools from the open source project Apache KIE." \
      org.opencontainers.image.licenses="Apache-2.0"

ENV RULE_ENGINE_DATA_DIR=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

RUN useradd --uid 1001 --create-home --shell /usr/sbin/nologin rules \
    && mkdir -p /data /app /licenses \
    && chown -R 1001:0 /data /app

COPY --from=build --chown=1001:0 /build/target/quarkus-app/lib/ /app/lib/
COPY --from=build --chown=1001:0 /build/target/quarkus-app/*.jar /app/
COPY --from=build --chown=1001:0 /build/target/quarkus-app/app/ /app/app/
COPY --from=build --chown=1001:0 /build/target/quarkus-app/quarkus/ /app/quarkus/
COPY --chown=1001:0 docker/healthcheck.sh /app/healthcheck.sh
COPY LICENSE NOTICE /licenses/

USER 1001
WORKDIR /app
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=6 CMD ["bash", "/app/healthcheck.sh"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
