package com.github.old_horizon.selenium.grid.node;

import com.google.common.cache.Cache;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.data.*;
import org.openqa.selenium.grid.log.LoggingOptions;
import org.openqa.selenium.grid.node.HealthCheck;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.node.local.LocalNode;
import org.openqa.selenium.grid.node.local.LocalNodeFactory;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.security.SecretOptions;
import org.openqa.selenium.grid.server.BaseServerOptions;
import org.openqa.selenium.internal.Either;
import org.openqa.selenium.io.TemporaryFilesystem;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.http.Route;
import org.openqa.selenium.remote.tracing.SpanDecorator;
import org.openqa.selenium.remote.tracing.Tracer;

import java.io.File;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.net.HttpURLConnection.HTTP_NO_CONTENT;
import static org.openqa.selenium.remote.http.Route.combine;
import static org.openqa.selenium.remote.http.Route.delete;

public class EnhancedLocalNode extends Node {

    private final LocalNode node;
    private final Route additionalRoutes;
    private final Cache<UUID, TemporaryFilesystem> downloadsTempFileSystem;
    private final Cache<SessionId, UUID> sessionToDownloadsDir;

    public static Node create(Config config) {
        var node = (LocalNode) LocalNodeFactory.create(config);
        return new EnhancedLocalNode(new LoggingOptions(config).getTracer(),
                node.getId(),
                new BaseServerOptions(config).getExternalUri(),
                new SecretOptions(config).getRegistrationSecret(),
                node);
    }

    @SuppressWarnings("unchecked")
    EnhancedLocalNode(Tracer tracer, NodeId id, URI uri, Secret registrationSecret, LocalNode node) {
        super(tracer, id, uri, registrationSecret);
        this.node = node;
        additionalRoutes = combine(
                delete("/session/{sessionId}/se/files")
                        .to(params -> new DeleteDownloadFile(this, sessionIdFrom(params)))
                        .with(spanDecorator("node.delete_download_file")));
        downloadsTempFileSystem = (Cache<UUID, TemporaryFilesystem>) getNodeField("downloadsTempFileSystem");
        sessionToDownloadsDir = (Cache<SessionId, UUID>) getNodeField("sessionToDownloadsDir");
    }

    @Override
    public boolean matches(HttpRequest req) {
        return additionalRoutes.matches(req) || node.matches(req);
    }

    @Override
    public HttpResponse execute(HttpRequest req) {
        return additionalRoutes.matches(req) ? additionalRoutes.execute(req) : node.execute(req);
    }

    HttpResponse deleteDownloadFile(SessionId id) {
        var uuid = Optional.ofNullable(sessionToDownloadsDir.getIfPresent(id))
                .orElseThrow(() -> new NoSuchSessionException("Cannot find session with id: " + id));
        var tempFS = Optional.ofNullable(downloadsTempFileSystem.getIfPresent(uuid))
                .orElseThrow(() -> new WebDriverException("Cannot find downloads file system for session id: " + id));
        Optional.ofNullable(tempFS.getBaseDir().listFiles()).map(dirs -> dirs[0].listFiles()).ifPresent(children -> {
            for (File child : children) {
                child.delete();
            }
        });
        return new HttpResponse().setStatus(HTTP_NO_CONTENT);
    }

    private SessionId sessionIdFrom(Map<String, String> params) {
        return new SessionId(params.get("sessionId"));
    }

    private SpanDecorator spanDecorator(String name) {
        return new SpanDecorator(tracer, req -> name);
    }

    private Object getNodeField(String name) {
        Field field;
        try {
            field = node.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(node);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Either<WebDriverException, CreateSessionResponse> newSession(CreateSessionRequest sessionRequest) {
        return node.newSession(sessionRequest);
    }

    @Override
    public HttpResponse executeWebDriverCommand(HttpRequest req) {
        return node.executeWebDriverCommand(req);
    }

    @Override
    public Session getSession(SessionId id) throws NoSuchSessionException {
        return node.getSession(id);
    }

    @Override
    public HttpResponse uploadFile(HttpRequest req, SessionId id) {
        return node.uploadFile(req, id);
    }

    @Override
    public HttpResponse downloadFile(HttpRequest req, SessionId id) {
        return node.downloadFile(req, id);
    }

    @Override
    public void stop(SessionId id) throws NoSuchSessionException {
        node.stop(id);
    }

    @Override
    public boolean isSessionOwner(SessionId id) {
        return node.isSessionOwner(id);
    }

    @Override
    public boolean isSupporting(Capabilities capabilities) {
        return node.isSupporting(capabilities);
    }

    @Override
    public NodeStatus getStatus() {
        return node.getStatus();
    }

    @Override
    public HealthCheck getHealthCheck() {
        return node.getHealthCheck();
    }

    @Override
    public void drain() {
        node.drain();
    }

    @Override
    public boolean isReady() {
        return node.isReady();
    }
}
