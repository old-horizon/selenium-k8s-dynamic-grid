# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-11 AS builder
COPY . /src
WORKDIR /src/worker
RUN --mount=type=cache,target=/root/.m2 mvn clean package -B

FROM selenium/standalone-chrome
USER root
COPY --from=builder /src/worker/target/*.jar /opt/selenium/worker-ext.jar
RUN sed -i 's/^EXTRA_LIBS="\(.*\)"/EXTRA_LIBS="\1:\/opt\/selenium\/worker-ext.jar"/' /opt/bin/start-selenium-standalone.sh
USER seluser
ENV SE_OPTS --node-implementation com.github.old_horizon.selenium.grid.node.EnhancedLocalNode \
--enable-managed-downloads true
