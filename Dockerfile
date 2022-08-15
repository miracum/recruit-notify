FROM docker.io/library/gradle:7.5-jdk17-focal@sha256:a40bb31550de12118cc67fcf3033a9d9de9b0c9661114a38f6bbd4036d389d95 AS build
WORKDIR /home/gradle/src
ENV GRADLE_USER_HOME /gradle

ARG OPENTELEMETRY_JAVA_AGENT_VERSION=1.16.0
RUN wget --quiet https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OPENTELEMETRY_JAVA_AGENT_VERSION}/opentelemetry-javaagent.jar

COPY build.gradle settings.gradle ./
RUN gradle clean build --no-daemon || true

COPY --chown=gradle:gradle . .

RUN gradle build --info && \
    gradle jacocoTestReport && \
    awk -F"," '{ instructions += $4 + $5; covered += $5 } END { print covered, "/", instructions, " instructions covered"; print 100*covered/instructions, "% covered" }' build/jacoco/coverage.csv && \
    java -Djarmode=layertools -jar build/libs/notify-0.0.1-SNAPSHOT.jar extract

FROM gcr.io/distroless/java17-debian11:nonroot@sha256:45e1a255d67d80cc3484c3cf4ff8b8e65d3b28bbda1c1d293874de5c79ee1075
WORKDIR /opt/notify
COPY --from=build /home/gradle/src/opentelemetry-javaagent.jar ./opentelemetry-javaagent.jar

COPY --from=build /home/gradle/src/dependencies/ ./
COPY --from=build /home/gradle/src/spring-boot-loader/ ./
COPY --from=build /home/gradle/src/snapshot-dependencies/ ./
COPY --from=build /home/gradle/src/application/ ./

USER 65532:65532
ARG VERSION=0.0.0
ENV NOTIFY_VERSION=${VERSION} \
    SPRING_PROFILES_ACTIVE="prod"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=90", "org.springframework.boot.loader.JarLauncher"]

ARG GIT_REF=""
ARG BUILD_TIME=""
LABEL maintainer="miracum.org" \
    org.opencontainers.image.created=${BUILD_TIME} \
    org.opencontainers.image.authors="miracum.org" \
    org.opencontainers.image.source="https://gitlab.miracum.org/miracum/uc1/recruit/notify" \
    org.opencontainers.image.version=${VERSION} \
    org.opencontainers.image.revision=${GIT_REF} \
    org.opencontainers.image.vendor="miracum.org" \
    org.opencontainers.image.title="recruit-notify" \
    org.opencontainers.image.description="MIRACUM recruIT notification module."
