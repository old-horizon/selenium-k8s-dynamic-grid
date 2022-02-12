package com.github.old_horizon.selenium.grid.hub.downloads;

import com.github.old_horizon.selenium.grid.hub.RemoteNodeRequestHandler;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;

import static org.openqa.selenium.remote.http.HttpMethod.DELETE;

public class DeleteFile extends RemoteNodeRequestHandler {

    private final String fileName;

    public DeleteFile(HttpHandler httpHandler, SessionId sessionId, String fileName) {
        super(httpHandler, sessionId);
        this.fileName = fileName;
    }

    @Override
    protected HttpRequest createNodeRequest() {
        return new HttpRequest(DELETE, String.format("/downloads/%s/%s", sessionId.toString(), fileName));
    }
}
