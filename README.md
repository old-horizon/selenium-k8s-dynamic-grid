# selenium-k8s-dynamic-grid

[![pipeline](https://github.com/old-horizon/selenium-k8s-dynamic-grid/actions/workflows/pipeline.yaml/badge.svg)](https://github.com/old-horizon/selenium-k8s-dynamic-grid/actions/workflows/pipeline.yaml)

This is the extension implements Selenium Grid 4's "Dynamic Grid" feature in Kubernetes cluster.

## Install

Deploying Helm Chart located in `kubernetes/helm` is the easiest way.

## Development

Use Skaffold with config file stored at `kubernetes/skaffold`.

## File download support

This extension has ability to access files downloaded with browser.

You can do the following operations by endpoints defined in Grid Hub.

* **GET /downloads/{sessionId}**  
Gets file list in "Downloads" directory as JSON format.
* **DELETE /downloads/{sessionId}**  
Deletes all files in "Downloads" directory.
* **GET /downloads/{sessionId}/{fileName}**  
Gets content of specified file.
* **DELETE /downloads/{sessionId}/{fileName}**  
Deletes specified file.

## Special thanks

This project is highly inspired by Zalenium and Selenoid, which provides same functions for Selenium Grid 3.
