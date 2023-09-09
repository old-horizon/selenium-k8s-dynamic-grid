package com.github.old_horizon.selenium.grid.commands.downloads;

import com.github.old_horizon.selenium.grid.node.KubernetesNode;
import com.github.old_horizon.selenium.grid.node.WorkerRequestHandler;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.io.UncheckedIOException;

import static org.openqa.selenium.remote.http.HttpMethod.DELETE;

public class DeleteFiles extends WorkerRequestHandler {

    public DeleteFiles(KubernetesNode node, SessionId sessionId) {
        super(node, sessionId);
    }

    @Override
    public HttpResponse execute(HttpRequest req) throws UncheckedIOException {
        return sendToWorker(new HttpRequest(DELETE, String.format("/session/%s/se/files", id.toString())));
    }
}
