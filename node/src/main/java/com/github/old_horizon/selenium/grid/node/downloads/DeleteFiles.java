package com.github.old_horizon.selenium.grid.node.downloads;

import com.github.old_horizon.selenium.grid.node.KubernetesSession;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.io.UncheckedIOException;

import static java.net.HttpURLConnection.HTTP_OK;

public class DeleteFiles implements HttpHandler {

    private final KubernetesSession session;

    public DeleteFiles(KubernetesSession session) {
        this.session = session;
    }

    @Override
    public HttpResponse execute(HttpRequest req) throws UncheckedIOException {
        session.getDownloadDirectory().deleteFiles();
        return new HttpResponse().setStatus(HTTP_OK);
    }
}
