package com.github.old_horizon.selenium.grid.node;

import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.io.UncheckedIOException;

public class DeleteDownloadFile implements HttpHandler {

    private final EnhancedLocalNode node;
    private final SessionId id;

    DeleteDownloadFile(EnhancedLocalNode node, SessionId id) {
        this.node = Require.nonNull("Node", node);
        this.id = Require.nonNull("Session id", id);
    }

    @Override
    public HttpResponse execute(HttpRequest req) throws UncheckedIOException {
        return node.deleteDownloadFile(id);
    }
}
