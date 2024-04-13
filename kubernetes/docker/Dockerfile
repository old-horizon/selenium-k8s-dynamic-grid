# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-11 AS builder
COPY . /src
WORKDIR /src
RUN --mount=type=cache,target=/root/.m2 mvn clean package -B

FROM eclipse-temurin:11-jre
ADD https://github.com/SeleniumHQ/selenium/releases/download/selenium-4.19.0/selenium-server-4.19.1.jar /opt/selenium/selenium-server.jar
COPY --from=builder /src/target/*.jar /opt/selenium/k8s-dynamic-grid.jar
COPY --from=builder /src/kubernetes/docker/start-selenium-dynamic-grid.sh /opt/bin/
EXPOSE 4444
CMD ["/opt/bin/start-selenium-dynamic-grid.sh"]
