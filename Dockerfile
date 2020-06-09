FROM gradle:6.5-jdk11 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle build --no-daemon --info

# Collect and print code coverage information:
RUN gradle --no-daemon jacocoTestReport
RUN awk -F"," '{ instructions += $4 + $5; covered += $5 } END { print covered, "/", instructions,\
    " instructions covered"; print 100*covered/instructions, "% covered" }' build/jacoco/coverage.csv

FROM gcr.io/distroless/java:11
WORKDIR /opt/notify
USER nonroot
COPY --from=build /home/gradle/src/build/libs/*.jar ./notify.jar
ARG VERSION=0.0.0
ARG GIT_REF=""
ARG BUILD_TIME=""
ENV APP_VERSION=${VERSION} \
    SPRING_PROFILES_ACTIVE="prod"
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=90", "-jar", "/opt/notify/notify.jar"]

LABEL maintainer="miracum.org" \
    org.opencontainers.image.created=${BUILD_TIME} \
    org.opencontainers.image.authors="miracum.org" \
    org.opencontainers.image.source="https://gitlab.miracum.org/miracum/uc1/recruit/notify" \
    org.opencontainers.image.version=${VERSION} \
    org.opencontainers.image.revision=${GIT_REF} \
    org.opencontainers.image.vendor="miracum.org" \
    org.opencontainers.image.title="uc1-recruit-notify" \
    org.opencontainers.image.description="Notification module of the patient recruitment system."
