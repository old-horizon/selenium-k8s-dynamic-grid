package com.github.old_horizon.selenium.grid.commands.downloads;

import com.github.old_horizon.selenium.grid.node.KubernetesSession;
import org.openqa.selenium.remote.http.HttpHandler;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.io.UncheckedIOException;

import static java.net.HttpURLConnection.HTTP_OK;

public class DeleteFile implements HttpHandler {

    private final KubernetesSession session;
    private final String fileName;

    public DeleteFile(KubernetesSession session, String fileName) {
        this.session = session;
        this.fileName = fileName;
    }

    @Override
    public HttpResponse execute(HttpRequest req) throws UncheckedIOException {
        session.getDownloadDirectory().deleteFile(fileName);
        return new HttpResponse().setStatus(HTTP_OK);
    }
}
