#
# Axon Ivy Rules Engine - decision runtime
# One generic image for all customers: DMN models are loaded at runtime through the API, never built into the image.
# The image contains the rule engine and its embedded database. Mount a volume at /data to keep the versions.
#
# The image of what Maven built. `mvn package` copies this file, LICENSE, NOTICE and the application to target/docker,
# which is all the image needs. In the root of the repository:
#
#   mvn package -f rule-engine-runtime
#   docker build -t axonivy/rule-engine-runtime:dev rule-engine-runtime/target/docker
#
# Without Java on the host, rule-engine-runtime/Dockerfile builds the same image with Maven inside Docker:
# docker build -t axonivy/rule-engine-runtime:dev rule-engine-runtime. DockerfileTest fails if the two do not
# describe the same image.
#
FROM eclipse-temurin:21-jre
LABEL org.opencontainers.image.title="Axon Ivy Rules Engine" \
      org.opencontainers.image.description="Decision runtime that loads DMN models at runtime. Based on Kogito and Drools from the open source project Apache KIE." \
      org.opencontainers.image.licenses="Apache-2.0"

ENV RULE_ENGINE_DATA_DIR=/data \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"

RUN useradd --uid 1001 --create-home --shell /usr/sbin/nologin rules \
    && mkdir -p /data /app /licenses \
    && chown -R 1001:0 /data /app

COPY --chown=1001:0 quarkus-app/lib/ /app/lib/
COPY --chown=1001:0 quarkus-app/*.jar /app/
COPY --chown=1001:0 quarkus-app/app/ /app/app/
COPY --chown=1001:0 quarkus-app/quarkus/ /app/quarkus/
COPY LICENSE NOTICE /licenses/

USER 1001
WORKDIR /app
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/quarkus-run.jar"]
