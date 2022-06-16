#!/usr/bin/env bash

set -e

if [ ! -z "$SE_OPTS" ]; then
  echo "Appending Selenium options: ${SE_OPTS}"
fi

java ${JAVA_OPTS} -jar /opt/selenium/selenium-server.jar \
  --ext /opt/selenium/k8s-dynamic-grid.jar \
  dynamic-grid \
  --config /opt/bin/config.toml \
  ${SE_OPTS}
