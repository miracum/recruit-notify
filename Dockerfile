FROM gradle:6.0.1-jdk11 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle build --no-daemon --info

FROM adoptopenjdk:11-jre-openj9
WORKDIR /opt/notify
COPY --from=build /home/gradle/src/build/libs/*.jar ./notify.jar
RUN useradd notify && chown -R notify:notify .
USER notify
ARG VERSION=0.0.0
ENV app.version=${VERSION}
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=85", "-jar", "/opt/notify/notify.jar"]
