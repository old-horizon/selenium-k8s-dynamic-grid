# syntax = docker/dockerfile:1.0-experimental
FROM maven:3.8-openjdk-11-slim AS builder
COPY . /src
WORKDIR /src/hub
RUN --mount=type=cache,target=/root/.m2 mvn clean package -B

FROM selenium/hub:4.1.4
COPY --from=builder /src/hub/target/*.jar /opt/selenium/k8s-dynamic-grid-hub.jar
USER root
RUN sed -i 's/ hub/ --ext \/opt\/selenium\/k8s-dynamic-grid-hub.jar dynamic-grid-hub/' /opt/bin/start-selenium-grid-hub.sh
USER 1200
