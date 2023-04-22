package com.github.old_horizon.selenium.grid.commands.downloads;

import com.github.old_horizon.selenium.grid.node.KubernetesNode;
import com.github.old_horizon.selenium.grid.node.WorkerRequestHandler;
import com.google.common.collect.ImmutableMap;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.json.TypeToken;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;

import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.util.Collections.singletonMap;
import static org.openqa.selenium.remote.http.Contents.asJson;
import static org.openqa.selenium.remote.http.Contents.string;
import static org.openqa.selenium.remote.http.HttpMethod.GET;

public class ListFiles extends WorkerRequestHandler {

    private final Type responseType = new TypeToken<Map<String, Map<String, List<String>>>>() {
    }.getType();

    public ListFiles(KubernetesNode node, SessionId id) {
        super(node, id);
    }

    @Override
    public HttpResponse execute(HttpRequest req) throws UncheckedIOException {
        var res = string(sendToWorker(new HttpRequest(GET, String.format("/session/%s/se/files", id.toString()))));
        Map<String, Map<String, List<String>>> json = new Json().toType(res, responseType);
        var files = json.get("value").get("names").stream()
                .map(e -> ImmutableMap.of("name", e)).collect(Collectors.toList());
        return new HttpResponse().setStatus(HTTP_OK).setContent(asJson(singletonMap("files", files)));
    }
}
