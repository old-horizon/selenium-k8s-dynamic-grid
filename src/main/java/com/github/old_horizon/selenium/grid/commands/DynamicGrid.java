package com.github.old_horizon.selenium.grid.commands;

import com.github.old_horizon.selenium.grid.commands.downloads.DeleteFile;
import com.github.old_horizon.selenium.grid.commands.downloads.DeleteFiles;
import com.github.old_horizon.selenium.grid.commands.downloads.GetFile;
import com.github.old_horizon.selenium.grid.commands.downloads.ListFiles;
import com.github.old_horizon.selenium.grid.distributor.LocalDistributor;
import com.github.old_horizon.selenium.grid.node.KubernetesNode;
import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableSet;
import org.openqa.selenium.BuildInfo;
import org.openqa.selenium.cli.CliCommand;
import org.openqa.selenium.grid.TemplateGridServerCommand;
import org.openqa.selenium.grid.commands.StandaloneFlags;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.Role;
import org.openqa.selenium.grid.data.NodeDrainComplete;
import org.openqa.selenium.grid.distributor.config.DistributorOptions;
import org.openqa.selenium.grid.graphql.GraphqlHandler;
import org.openqa.selenium.grid.log.LoggingOptions;
import org.openqa.selenium.grid.node.ProxyNodeWebsockets;
import org.openqa.selenium.grid.router.Router;
import org.openqa.selenium.grid.security.BasicAuthenticationFilter;
import org.openqa.selenium.grid.security.SecretOptions;
import org.openqa.selenium.grid.server.BaseServerOptions;
import org.openqa.selenium.grid.server.EventBusOptions;
import org.openqa.selenium.grid.server.NetworkOptions;
import org.openqa.selenium.grid.sessionmap.local.LocalSessionMap;
import org.openqa.selenium.grid.sessionqueue.config.NewSessionQueueOptions;
import org.openqa.selenium.grid.sessionqueue.local.LocalNewSessionQueue;
import org.openqa.selenium.grid.web.CombinedHandler;
import org.openqa.selenium.grid.web.GridUiRoute;
import org.openqa.selenium.grid.web.RoutableHttpClientFactory;
import org.openqa.selenium.internal.Require;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.*;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAVAILABLE;
import static org.openqa.selenium.grid.config.StandardGridRoles.*;
import static org.openqa.selenium.remote.http.Route.combine;

@AutoService(CliCommand.class)
public class DynamicGrid extends TemplateGridServerCommand {

    private static final Logger LOG = Logger.getLogger(DynamicGrid.class.getName());

    @Override
    public String getName() {
        return "dynamic-grid";
    }

    @Override
    public String getDescription() {
        return "Dynamic selenium grid, which runs worker in kubernetes cluster.";
    }

    @Override
    public Set<Role> getConfigurableRoles() {
        return ImmutableSet.of(DISTRIBUTOR_ROLE, HTTPD_ROLE, NODE_ROLE, ROUTER_ROLE, SESSION_QUEUE_ROLE);
    }

    @Override
    public Set<Object> getFlagObjects() {
        return Collections.singleton(new StandaloneFlags());
    }

    @Override
    protected String getSystemPropertiesConfigPrefix() {
        return "selenium";
    }

    @Override
    protected Config getDefaultConfig() {
        return new DefaultDynamicGridConfig();
    }

    @Override
    protected Handlers createHandlers(Config config) {
        var loggingOptions = new LoggingOptions(config);
        var tracer = loggingOptions.getTracer();

        var events = new EventBusOptions(config);
        var bus = events.getEventBus();

        var serverOptions = new BaseServerOptions(config);
        var secretOptions = new SecretOptions(config);
        var registrationSecret = secretOptions.getRegistrationSecret();

        var localhost = serverOptions.getExternalUri();
        URL localhostUrl;
        try {
            localhostUrl = localhost.toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }

        var networkOptions = new NetworkOptions(config);
        var combinedHandler = new CombinedHandler();
        var clientFactory = new RoutableHttpClientFactory(
                localhostUrl,
                combinedHandler,
                networkOptions.getHttpClientFactory(tracer));

        var sessions = new LocalSessionMap(tracer, bus);
        combinedHandler.addHandler(sessions);

        var distributorOptions = new DistributorOptions(config);
        var newSessionRequestOptions = new NewSessionQueueOptions(config);
        var queue = new LocalNewSessionQueue(
                tracer,
                distributorOptions.getSlotMatcher(),
                newSessionRequestOptions.getSessionRequestRetryInterval(),
                newSessionRequestOptions.getSessionRequestTimeout(),
                registrationSecret);
        combinedHandler.addHandler(queue);

        var distributor = new LocalDistributor(
                tracer,
                bus,
                clientFactory,
                sessions,
                queue,
                distributorOptions.getSlotSelector(),
                registrationSecret,
                distributorOptions.getHealthCheckInterval(),
                distributorOptions.shouldRejectUnsupportedCaps(),
                newSessionRequestOptions.getSessionRequestRetryInterval());
        combinedHandler.addHandler(distributor);

        var router = new Router(tracer, clientFactory, sessions, queue, distributor)
                .with(networkOptions.getSpecComplianceChecks());

        HttpHandler readinessCheck = req -> {
            var ready = sessions.isReady() && distributor.isReady() && bus.isReady();
            return new HttpResponse()
                    .setStatus(ready ? HTTP_OK : HTTP_UNAVAILABLE)
                    .setContent(Contents.utf8String("Dynamic grid is " + ready));
        };

        var graphqlHandler = new GraphqlHandler(
                tracer,
                distributor,
                queue,
                serverOptions.getExternalUri(),
                getFormattedVersion());

        var ui = new GridUiRoute();

        var node = (KubernetesNode) KubernetesNode.create(config);
        combinedHandler.addHandler(node);
        distributor.add(node);

        Routable httpHandler = combine(
                ui,
                router,
                Route.get("/readyz").to(() -> readinessCheck),
                Route.prefix("/wd/hub").to(combine(router)),
                Route.options("/graphql").to(() -> graphqlHandler),
                Route.post("/graphql").to(() -> graphqlHandler),
                Route.get("/downloads/{sessionId}/{fileName}")
                        .to(params -> new GetFile(node.getKubernetesSession(sessionIdFrom(params)), fileNameFrom(params))),
                Route.delete("/downloads/{sessionId}/{fileName}")
                        .to(params -> new DeleteFile(node.getKubernetesSession(sessionIdFrom(params)), fileNameFrom(params))),
                Route.get("/downloads/{sessionId}")
                        .to(params -> new ListFiles(node.getKubernetesSession(sessionIdFrom(params)))),
                Route.delete("/downloads/{sessionId}")
                        .to(params -> new DeleteFiles(node.getKubernetesSession(sessionIdFrom(params)))));

        var uap = secretOptions.getServerAuthentication();
        if (uap != null) {
            LOG.info("Requiring authentication to connect");
            httpHandler = httpHandler.with(new BasicAuthenticationFilter(uap.username(), uap.password()));
        }

        bus.addListener(NodeDrainComplete.listener(nodeId -> {
            if (!node.getId().equals(nodeId)) {
                return;
            }

            new Thread(
                    () -> {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignore) {
                        }
                        LOG.info("Shutting down");
                        System.exit(0);
                    },
                    "Dynamic grid shutdown: " + nodeId)
                    .start();
        }));

        return new Handlers(httpHandler, new ProxyNodeWebsockets(clientFactory, node));
    }

    @Override
    protected void execute(Config config) {
        Require.nonNull("Config", config);

        var server = asServer(config).start();

        LOG.info(String.format(
                "Started Selenium Dynamic Grid %s: %s",
                getFormattedVersion(),
                server.getUrl()));
    }

    private SessionId sessionIdFrom(Map<String, String> params) {
        return new SessionId(params.get("sessionId"));
    }

    private String fileNameFrom(Map<String, String> params) {
        return params.get("fileName");
    }

    private String getFormattedVersion() {
        var info = new BuildInfo();
        return String.format("%s (revision %s)", info.getReleaseLabel(), info.getBuildRevision());
    }
}
