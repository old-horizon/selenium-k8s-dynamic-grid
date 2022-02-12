package com.github.old_horizon.selenium.grid.hub;

import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.Map;

import static org.openqa.selenium.json.Json.MAP_TYPE;
import static org.openqa.selenium.remote.http.Contents.string;
import static org.openqa.selenium.remote.http.HttpMethod.GET;

public abstract class RemoteNodeRequestHandler implements HttpHandler {

    private static final Json JSON = new Json();

    protected final SessionId sessionId;

    private final HttpHandler httpHandler;

    public RemoteNodeRequestHandler(HttpHandler httpHandler, SessionId sessionId) {
        this.httpHandler = httpHandler;
        this.sessionId = sessionId;
    }

    protected abstract HttpRequest createNodeRequest();

    @Override
    public HttpResponse execute(HttpRequest req) throws UncheckedIOException {
        try (var client = HttpClient.Factory.createDefault().createClient(getNodeUri().toURL())) {
            return client.execute(createNodeRequest());
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    URI getNodeUri() {
        var response = httpHandler.execute(new HttpRequest(GET, "/se/grid/session/" + sessionId.toString()));
        Map<String, Object> json = JSON.toType(string(response), MAP_TYPE);
        String uri = (String) ((Map<String, Object>) json.get("value")).get("uri");
        return URI.create(uri);
    }
}
