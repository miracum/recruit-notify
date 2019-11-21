FROM gradle:6.0.1-jdk11 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN gradle build --no-daemon --info

FROM openjdk:11-jre-slim
COPY --from=build /home/gradle/src/build/libs/*.jar /opt/notify.jar
ARG VERSION=0.0.0
ENV app.version=${VERSION}
ENTRYPOINT ["java", "-jar", "/opt/notify.jar"]
