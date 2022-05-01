# syntax = docker/dockerfile:1.0-experimental
FROM maven:3.8-openjdk-11-slim AS builder
COPY . /src
WORKDIR /src/node
RUN --mount=type=cache,target=/root/.m2 mvn clean package -B

FROM openjdk:11-jre-slim
ADD https://github.com/SeleniumHQ/selenium/releases/download/selenium-4.1.0/selenium-server-4.1.4.jar /opt/selenium/selenium-server.jar
COPY --from=builder /src/node/target/*.jar /opt/selenium/k8s-dynamic-grid-node.jar
COPY --from=builder /src/kubernetes/docker/node/start-selenium-grid-kubernetes.sh /opt/bin/
EXPOSE 5555
CMD ["/opt/bin/start-selenium-grid-kubernetes.sh"]
