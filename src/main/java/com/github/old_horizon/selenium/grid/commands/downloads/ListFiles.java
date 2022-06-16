package com.github.old_horizon.selenium.grid.commands.downloads;

import com.github.old_horizon.selenium.grid.node.KubernetesSession;
import com.google.common.collect.ImmutableMap;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.io.UncheckedIOException;
import java.util.stream.Collectors;

import static java.net.HttpURLConnection.HTTP_OK;
import static org.openqa.selenium.remote.http.Contents.utf8String;

public class ListFiles implements HttpHandler {

    private static final Json JSON = new Json();

    private final KubernetesSession session;

    public ListFiles(KubernetesSession session) {
        this.session = session;
    }

    @Override
    public HttpResponse execute(HttpRequest req) throws UncheckedIOException {
        var files = session.getDownloadDirectory().listFiles().stream()
                .map(e -> ImmutableMap.of("name", e)).collect(Collectors.toList());
        var json = ImmutableMap.of("files", files);
        return new HttpResponse().setStatus(HTTP_OK).setContent(utf8String(JSON.toJson(json)));
    }
}
