package com.github.old_horizon.selenium.grid.node;

import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

public abstract class WorkerRequestHandler implements HttpHandler {
    
    protected final KubernetesNode node;
    protected final SessionId id;

    public WorkerRequestHandler(KubernetesNode node, SessionId id) {
        this.node = node;
        this.id = id;
    }

    protected HttpResponse sendToWorker(HttpRequest req) {
        return node.executeWorkerRequest(id, req);
    }
}
