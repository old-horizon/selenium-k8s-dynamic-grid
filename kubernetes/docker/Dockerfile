# syntax = docker/dockerfile:1.0-experimental
FROM maven:3.8-eclipse-temurin-11 AS builder
COPY . /src
WORKDIR /src
RUN --mount=type=cache,target=/root/.m2 mvn clean package -B

FROM eclipse-temurin:11-jre
ADD https://github.com/SeleniumHQ/selenium/releases/download/selenium-4.5.0/selenium-server-4.5.0.jar /opt/selenium/selenium-server.jar
COPY --from=builder /src/target/*.jar /opt/selenium/k8s-dynamic-grid.jar
COPY --from=builder /src/kubernetes/docker/start-selenium-dynamic-grid.sh /opt/bin/
EXPOSE 4444
CMD ["/opt/bin/start-selenium-dynamic-grid.sh"]
