package com.github.old_horizon.selenium.grid.hub.downloads;

import com.github.old_horizon.selenium.grid.hub.RemoteNodeRequestHandler;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;

import static org.openqa.selenium.remote.http.HttpMethod.DELETE;

public class DeleteFiles extends RemoteNodeRequestHandler {

    public DeleteFiles(HttpHandler httpHandler, SessionId sessionId) {
        super(httpHandler, sessionId);
    }

    @Override
    protected HttpRequest createNodeRequest() {
        return new HttpRequest(DELETE, String.format("/downloads/%s", sessionId.toString()));
    }
}
