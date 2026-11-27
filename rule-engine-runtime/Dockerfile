# syntax=docker/dockerfile:1
#
# Axon Ivy Rules Engine - decision runtime, built without Java or Maven on the host.
# Maven runs inside Docker and writes target/docker, as `mvn package` does on a host. The image is then built from
# what it wrote, with the instructions of src/main/docker/Dockerfile: the image is described there.
# DockerfileTest fails if the stage of the image below is not the same as that file.
#
# Built from this folder; it needs nothing else of the repository. In the root of the repository:
#
#   docker build -t axonivy/rule-engine-runtime:dev rule-engine-runtime
#
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml LICENSE NOTICE ./
COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="Axon Ivy Rules Engine" \
      org.opencontainers.image.description="Decision runtime that loads DMN models at runtime. Based on Kogito and Drools from the open source project Apache KIE." \
      org.opencontainers.image.licenses="Apache-2.0"

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

RUN useradd --uid 1001 --create-home --shell /usr/sbin/nologin rules \
    && mkdir -p /app /licenses \
    && chown -R 1001:0 /app

COPY --from=build --chown=1001:0 /build/target/docker/quarkus-app/lib/ /app/lib/
COPY --from=build --chown=1001:0 /build/target/docker/quarkus-app/*.jar /app/
COPY --from=build --chown=1001:0 /build/target/docker/quarkus-app/app/ /app/app/
COPY --from=build --chown=1001:0 /build/target/docker/quarkus-app/quarkus/ /app/quarkus/
COPY --from=build /build/target/docker/LICENSE /build/target/docker/NOTICE /licenses/

USER 1001
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
