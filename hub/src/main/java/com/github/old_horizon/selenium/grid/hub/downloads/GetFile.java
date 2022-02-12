package com.github.old_horizon.selenium.grid.hub.downloads;

import com.github.old_horizon.selenium.grid.hub.RemoteNodeRequestHandler;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;

import static org.openqa.selenium.remote.http.HttpMethod.GET;

public class GetFile extends RemoteNodeRequestHandler {

    private final String fileName;

    public GetFile(HttpHandler httpHandler, SessionId sessionId, String fileName) {
        super(httpHandler, sessionId);
        this.fileName = fileName;
    }

    @Override
    protected HttpRequest createNodeRequest() {
        return new HttpRequest(GET, String.format("/downloads/%s/%s", sessionId.toString(), fileName));
    }
}
