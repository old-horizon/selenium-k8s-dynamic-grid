package com.github.old_horizon.selenium.grid.node;

import com.github.old_horizon.selenium.k8s.KubernetesDriver;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalNotification;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.openqa.selenium.*;
import org.openqa.selenium.concurrent.GuardedRunnable;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.ConfigException;
import org.openqa.selenium.grid.data.*;
import org.openqa.selenium.grid.jmx.JMXHelper;
import org.openqa.selenium.grid.jmx.ManagedAttribute;
import org.openqa.selenium.grid.jmx.ManagedService;
import org.openqa.selenium.grid.log.LoggingOptions;
import org.openqa.selenium.grid.node.ActiveSession;
import org.openqa.selenium.grid.node.HealthCheck;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.node.config.NodeOptions;
import org.openqa.selenium.grid.node.local.SessionSlot;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.security.SecretOptions;
import org.openqa.selenium.grid.server.BaseServerOptions;
import org.openqa.selenium.grid.server.EventBusOptions;
import org.openqa.selenium.grid.server.NetworkOptions;
import org.openqa.selenium.internal.Debug;
import org.openqa.selenium.internal.Either;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.tracing.AttributeMap;
import org.openqa.selenium.remote.tracing.Status;
import org.openqa.selenium.remote.tracing.Tracer;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.openqa.selenium.grid.data.Availability.DRAINING;
import static org.openqa.selenium.grid.data.Availability.UP;
import static org.openqa.selenium.grid.node.CapabilityResponseEncoder.getEncoder;
import static org.openqa.selenium.remote.HttpSessionId.getSessionId;
import static org.openqa.selenium.remote.RemoteTags.CAPABILITIES;
import static org.openqa.selenium.remote.RemoteTags.SESSION_ID;
import static org.openqa.selenium.remote.http.HttpMethod.DELETE;
import static org.openqa.selenium.remote.tracing.AttributeKey.*;

@ManagedService(objectName = "org.seleniumhq.grid:type=Node,name=KubernetesNode",
        description = "Node which creates worker pod per session in kubernetes cluster.")
public class KubernetesNode extends Node {

    private static final Logger LOG = Logger.getLogger(KubernetesNode.class.getName());

    private final EventBus bus;
    private final URI uri;
    private final List<SessionSlot> factories;
    private final int maxSessionCount;
    private final boolean cdpEnabled;
    private final boolean bidiEnabled;
    private final Duration heartbeatPeriod;
    private final Cache<SessionId, SessionSlot> currentSessions;
    private final AtomicInteger pendingSessions = new AtomicInteger();

    public KubernetesNode(Tracer tracer, EventBus bus, Secret registrationSecret, NodeId nodeId,
                          URI uri, List<SessionSlot> factories, int maxSessionCount, boolean cdpEnabled,
                          boolean bidiEnabled, Duration sessionTimeout, Duration heartbeatPeriod) {
        super(tracer, nodeId, uri, registrationSecret);
        this.bus = bus;
        this.uri = uri;
        this.factories = factories;
        this.maxSessionCount = maxSessionCount;
        this.cdpEnabled = cdpEnabled;
        this.bidiEnabled = bidiEnabled;
        this.heartbeatPeriod = heartbeatPeriod;
        this.currentSessions = CacheBuilder.newBuilder()
                .expireAfterAccess(sessionTimeout)
                .ticker(Ticker.systemTicker())
                .removalListener(this::stopTimedOutSession)
                .build();

        var sessionCleanupNodeService = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("Kubernetes Node - Session Cleanup " + uri);
            return thread;
        });
        sessionCleanupNodeService.scheduleAtFixedRate(
                GuardedRunnable.guard(currentSessions::cleanUp), 30, 30, TimeUnit.SECONDS);

        var heartbeatNodeService = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r);
            thread.setDaemon(true);
            thread.setName("HeartBeat Node " + uri);
            return thread;
        });
        heartbeatNodeService.scheduleAtFixedRate(
                GuardedRunnable.guard(() -> bus.fire(new NodeHeartBeatEvent(getStatus()))),
                heartbeatPeriod.getSeconds(), heartbeatPeriod.getSeconds(), TimeUnit.SECONDS);

        bus.addListener(SessionClosedEvent.listener(id -> {
            if (this.isDraining() && pendingSessions.decrementAndGet() <= 0) {
                LOG.info("Firing node drain complete message");
                bus.fire(new NodeDrainComplete(this.getId()));
            }
        }));

        Runtime.getRuntime().addShutdownHook(new Thread(this::stopAllSessions));
        new JMXHelper().register(this);
    }

    private void stopTimedOutSession(RemovalNotification<SessionId, SessionSlot> notification) {
        if (notification.getKey() != null && notification.getValue() != null) {
            var slot = notification.getValue();
            var id = notification.getKey();
            if (notification.wasEvicted()) {
                LOG.log(Level.INFO, () -> String.format("Session id %s timed out, stopping...", id));
                try {
                    slot.execute(new HttpRequest(DELETE, "/session/" + id));
                } catch (Exception e) {
                    LOG.log(Level.WARNING, String.format("Exception while trying to stop session %s", id), e);
                }
            }
            slot.stop();
            if (this.isDraining()) {
                var done = pendingSessions.decrementAndGet();
                if (done <= 0) {
                    LOG.info("Node draining complete!");
                    bus.fire(new NodeDrainComplete(this.getId()));
                }
            }
        } else {
            LOG.log(Debug.getDebugLogLevel(), "Received stop session notification with null values");
        }
    }

    public static Node create(Config config) {
        var loggingOptions = new LoggingOptions(config);
        var eventOptions = new EventBusOptions(config);
        var serverOptions = new BaseServerOptions(config);
        var nodeOptions = new NodeOptions(config);
        var secretOptions = new SecretOptions(config);
        var networkOptions = new NetworkOptions(config);
        var k8sOptions = new KubernetesOptions(config);

        var tracer = loggingOptions.getTracer();
        var bus = eventOptions.getEventBus();
        var clientFactory = networkOptions.getHttpClientFactory(tracer);
        var k8s = new KubernetesDriver(new KubernetesClientBuilder().build());
        var factories = createFactories(k8sOptions, tracer, clientFactory, bus, k8s);

        LOG.info("Creating kubernetes node");

        return new KubernetesNode(tracer, bus, secretOptions.getRegistrationSecret(), new NodeId(UUID.randomUUID()),
                serverOptions.getExternalUri(), factories, k8sOptions.getMaxSessions(), nodeOptions.isCdpEnabled(),
                nodeOptions.isBiDiEnabled(), k8sOptions.getSessionTimeout(), k8sOptions.getHeartbeatPeriod());
    }

    static List<SessionSlot> createFactories(KubernetesOptions k8sOptions, Tracer tracer,
                                             HttpClient.Factory clientFactory, EventBus eventBus,
                                             KubernetesDriver driver) {
        var configs = k8sOptions.getConfigs();
        if (configs.isEmpty()) {
            throw new ConfigException("Unable to find kubernetes configs");
        }
        return configs.stream().flatMap(c -> Collections.nCopies(k8sOptions.getMaxSessions(), c).stream())
                .map(config -> {
                    var image = config.getImage();
                    var stereoType = config.getStereoType();
                    var factory = new KubernetesSessionFactory(tracer, clientFactory, driver,
                            k8sOptions.getWorkerStartupTimeout(), k8sOptions.getWorkerResourceRequests(),
                            image, k8sOptions.getWorkerImagePullPolicy(), stereoType, k8sOptions.getVideoImage(),
                            k8sOptions.getVideoStartupTimeout(), k8sOptions.getVideoImagePullPolicy(),
                            k8sOptions.getVideosPath());
                    return new SessionSlot(eventBus, stereoType, factory);
                }).collect(Collectors.toList());
    }

    @Override
    public Either<WebDriverException, CreateSessionResponse> newSession(CreateSessionRequest sessionRequest) {
        try (var span = tracer.getCurrentContext().createSpan("kubernetes_node.new_session")) {
            var desiredCapabilities = sessionRequest.getDesiredCapabilities();

            AttributeMap attributeMap = tracer.createAttributeMap();
            attributeMap.put(LOGGER_CLASS.getKey(), getClass().getName());
            attributeMap.put("session.request.capabilities", desiredCapabilities.toString());
            attributeMap.put("session.request.downstreamdialect", sessionRequest.getDownstreamDialects().toString());

            var currentSessionCount = getCurrentSessionCount();
            span.setAttribute("current.session.count", currentSessionCount);
            attributeMap.put("current.session.count", currentSessionCount);

            if (getCurrentSessionCount() >= maxSessionCount) {
                span.setAttribute("error", true);
                span.setStatus(Status.RESOURCE_EXHAUSTED);
                attributeMap.put("max.session.count", maxSessionCount);
                span.addEvent("Max session count reached", attributeMap);
                return Either.left(new RetrySessionRequestException("Max session count reached."));
            }

            SessionSlot slotToUse = null;
            synchronized (factories) {
                for (var factory : factories) {
                    if (!factory.isAvailable() || !factory.test(desiredCapabilities)) {
                        continue;
                    }
                    factory.reserve();
                    slotToUse = factory;
                    break;
                }
            }

            if (slotToUse == null) {
                span.setAttribute("error", true);
                span.setStatus(Status.NOT_FOUND);
                span.addEvent("No slot matched the requested capabilities. ", attributeMap);
                return Either.left(new RetrySessionRequestException("No slot matched the requested capabilities."));
            }

            Either<WebDriverException, ActiveSession> possibleSession = slotToUse.apply(sessionRequest);

            if (possibleSession.isRight()) {
                var session = possibleSession.right();
                currentSessions.put(session.getId(), slotToUse);

                SESSION_ID.accept(span, session.getId());
                var caps = session.getCapabilities();
                CAPABILITIES.accept(span, caps);
                span.setAttribute(DOWNSTREAM_DIALECT.getKey(), session.getDownstreamDialect().toString());
                span.setAttribute(UPSTREAM_DIALECT.getKey(), session.getUpstreamDialect().toString());
                span.setAttribute(SESSION_URI.getKey(), session.getUri().toString());

                var externalSession = createExternalSession(session, uri, slotToUse.isSupportingCdp(),
                        slotToUse.isSupportingBiDi(), desiredCapabilities);
                return Either.right(new CreateSessionResponse(externalSession,
                        getEncoder(session.getDownstreamDialect()).apply(externalSession)));
            } else {
                slotToUse.release();
                span.setAttribute("error", true);
                span.addEvent("Unable to create session with the driver", attributeMap);
                return Either.left(possibleSession.left());
            }
        }
    }

    @Override
    public HttpResponse executeWebDriverCommand(HttpRequest req) {
        var id = getSessionId(req.getUri()).map(SessionId::new)
                .orElseThrow(() -> new NoSuchSessionException("Cannot find session: " + req));
        var slot = getSessionSlot(id);

        var toReturn = slot.execute(req);
        if (req.getMethod() == DELETE && req.getUri().equals("/session/" + id)) {
            stop(id);
        }
        return toReturn;
    }

    @Override
    public Session getSession(SessionId id) throws NoSuchSessionException {
        var slot = getSessionSlot(id);
        return createExternalSession(slot.getSession(), uri, slot.isSupportingCdp(), slot.isSupportingBiDi(),
                slot.getSession().getCapabilities());
    }

    @Override
    public HttpResponse downloadFile(HttpRequest req, SessionId id) {
        return executeWebDriverCommand(req);
    }

    @Override
    public HttpResponse uploadFile(HttpRequest req, SessionId id) {
        return executeWebDriverCommand(req);
    }

    @Override
    public void stop(SessionId id) throws NoSuchSessionException {
        getSessionSlot(id);
        currentSessions.invalidate(id);
    }

    @Override
    public boolean isSessionOwner(SessionId id) {
        return currentSessions.getIfPresent(id) != null;
    }

    @Override
    public boolean isSupporting(Capabilities capabilities) {
        return factories.parallelStream().anyMatch(factory -> factory.test(capabilities));
    }

    @Override
    public NodeStatus getStatus() {
        return new NodeStatus(getId(), uri, maxSessionCount, getSlots(), isDraining() ? DRAINING : UP, heartbeatPeriod,
                getNodeVersion(), getOsInfo());
    }

    @Override
    public HealthCheck getHealthCheck() {
        var availability = isDraining() ? DRAINING : UP;
        return () -> new HealthCheck.Result(availability, String.format("%s is %s", uri,
                availability.name().toLowerCase()));
    }

    @Override
    public void drain() {
        bus.fire(new NodeDrainStarted(getId()));
        draining = true;
        var currentSessionCount = getCurrentSessionCount();
        if (currentSessionCount == 0) {
            LOG.info("Firing node drain complete message");
            bus.fire(new NodeDrainComplete(getId()));
        } else {
            pendingSessions.set(currentSessionCount);
        }
    }

    @Override
    public boolean isReady() {
        return bus.isReady();
    }

    @ManagedAttribute(name = "CurrentSessions")
    public int getCurrentSessionCount() {
        return Math.toIntExact(currentSessions.size());
    }

    HttpResponse executeWorkerRequest(SessionId id, HttpRequest req) {
        return getSessionSlot(id).getSession().execute(req);
    }

    private void stopAllSessions() {
        if (currentSessions.size() > 0) {
            LOG.info("Trying to stop all running sessions before shutting down...");
            currentSessions.invalidateAll();
        }
    }

    private Session createExternalSession(ActiveSession other, URI externalUri, boolean isSupportingCdp,
                                          boolean isSupportingBiDi, Capabilities requestCapabilities) {
        Capabilities toUse = ImmutableCapabilities.copyOf(requestCapabilities.merge(other.getCapabilities()));

        if ((isSupportingCdp || toUse.getCapability("se:cdp") != null) && cdpEnabled) {
            var cdpPath = String.format("/session/%s/se/cdp", other.getId());
            toUse = new PersistentCapabilities(toUse).setCapability("se:cdp", rewrite(cdpPath));
        } else {
            toUse = new PersistentCapabilities(excludePrefixOf(toUse, "se:cdp"))
                    .setCapability("se:cdpEnabled", false);
        }

        if ((isSupportingBiDi || toUse.getCapability("se:bidi") != null) && bidiEnabled) {
            var bidiPath = String.format("/session/%s/se/bidi", other.getId());
            toUse = new PersistentCapabilities(toUse).setCapability("se:bidi", rewrite(bidiPath));
        } else {
            toUse = new PersistentCapabilities(excludePrefixOf(toUse, "se:bidi"))
                    .setCapability("se:bidiEnabled", false);
        }

        var isVncEnabled = toUse.getCapability("se:vncLocalAddress") != null;
        if (isVncEnabled) {
            var vncPath = String.format("/session/%s/se/vnc", other.getId());
            toUse = new PersistentCapabilities(toUse).setCapability("se:vnc", rewrite(vncPath));
        }

        return new Session(other.getId(), externalUri, other.getStereotype(), toUse, Instant.now());
    }

    private Capabilities excludePrefixOf(Capabilities capabilities, String prefix) {
        var excluded = new MutableCapabilities();
        capabilities.asMap().entrySet().stream().filter(e -> !e.getKey().startsWith(prefix))
                .forEach(e -> excluded.setCapability(e.getKey(), e.getValue()));
        return excluded;
    }

    private URI rewrite(String path) {
        try {
            var scheme = "https".equals(uri.getScheme()) ? "wss" : "ws";
            if (uri.getPath() != null && !uri.getPath().equals("/")) {
                path = uri.getPath() + path;
            }
            return new URI(scheme, uri.getUserInfo(), uri.getHost(), uri.getPort(), path, null, null);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    private SessionSlot getSessionSlot(SessionId id) {
        return Optional.ofNullable(currentSessions.getIfPresent(id))
                .orElseThrow(() -> new NoSuchSessionException("Cannot find session with id: " + id));
    }

    private Set<Slot> getSlots() {
        return factories.stream().map(factory ->
                        factory.isAvailable() ? toAvailableSlot(factory) : toUnavailableSlot(factory))
                .collect(Collectors.toUnmodifiableSet());
    }

    private Slot toAvailableSlot(SessionSlot slot) {
        return new Slot(new SlotId(getId(), slot.getId()), slot.getStereotype(), Instant.EPOCH, null);
    }

    private Slot toUnavailableSlot(SessionSlot slot) {
        return new Slot(new SlotId(getId(), slot.getId()), slot.getStereotype(), Optional.ofNullable(slot.getSession())
                .map(ActiveSession::getStartTime).orElse(Instant.EPOCH), null);
    }
}
