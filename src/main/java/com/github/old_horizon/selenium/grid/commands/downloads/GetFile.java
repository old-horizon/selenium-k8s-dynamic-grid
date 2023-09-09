package com.github.old_horizon.selenium.grid.commands.downloads;

import com.github.old_horizon.selenium.grid.node.KubernetesNode;
import com.github.old_horizon.selenium.grid.node.WorkerRequestHandler;
import com.google.common.io.ByteStreams;
import org.openqa.selenium.io.TemporaryFilesystem;
import org.openqa.selenium.io.Zip;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.json.TypeToken;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.Map;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.util.Collections.singletonMap;
import static org.openqa.selenium.remote.http.Contents.*;
import static org.openqa.selenium.remote.http.HttpMethod.POST;

public class GetFile extends WorkerRequestHandler {

    private final String fileName;
    private final Type responseType = new TypeToken<Map<String, Map<String, String>>>() {
    }.getType();
    private final TemporaryFilesystem tmpFs = TemporaryFilesystem.getDefaultTmpFS();

    public GetFile(KubernetesNode node, SessionId id, String fileName) {
        super(node, id);
        this.fileName = fileName;
    }

    @Override
    public HttpResponse execute(HttpRequest req) throws UncheckedIOException {
        var res = string(sendToWorker(new HttpRequest(POST, String.format("/session/%s/se/files", id.toString()))
                .setContent(asJson(singletonMap("name", fileName)))));
        Map<String, Map<String, String>> json = new Json().toType(res, responseType);
        var contents = json.get("value").get("contents");
        try {
            var dir = tmpFs.createTempDir("files", id.toString());
            Zip.unzip(contents, dir);
            try (var in = Files.newInputStream(dir.toPath().resolve(fileName));
                 var out = new ByteArrayOutputStream()) {
                ByteStreams.copy(in, out);
                return new HttpResponse().setStatus(HTTP_OK).setContent(bytes(out.toByteArray()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            tmpFs.deleteTemporaryFiles();
        }
    }
}
