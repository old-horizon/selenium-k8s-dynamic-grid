package com.github.old_horizon.selenium.grid.node;

import com.github.old_horizon.selenium.grid.node.downloads.DeleteFile;
import com.github.old_horizon.selenium.grid.node.downloads.DeleteFiles;
import com.github.old_horizon.selenium.grid.node.downloads.GetFile;
import com.github.old_horizon.selenium.grid.node.downloads.ListFiles;
import com.github.old_horizon.selenium.k8s.KubernetesDriver;
import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import org.openqa.selenium.*;
import org.openqa.selenium.concurrent.Regularly;
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
import org.openqa.selenium.remote.http.Route;
import org.openqa.selenium.remote.tracing.EventAttributeValue;
import org.openqa.selenium.remote.tracing.Status;
import org.openqa.selenium.remote.tracing.Tracer;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.openqa.selenium.grid.data.Availability.DRAINING;
import static org.openqa.selenium.grid.data.Availability.UP;
import static org.openqa.selenium.grid.node.CapabilityResponseEncoder.getEncoder;
import static org.openqa.selenium.remote.HttpSessionId.getSessionId;
import static org.openqa.selenium.remote.RemoteTags.CAPABILITIES;
import static org.openqa.selenium.remote.RemoteTags.SESSION_ID;
import static org.openqa.selenium.remote.http.HttpMethod.DELETE;
import static org.openqa.selenium.remote.http.Route.*;
import static org.openqa.selenium.remote.tracing.AttributeKey.*;
import static org.openqa.selenium.remote.tracing.EventAttribute.setValue;

@ManagedService(objectName = "org.seleniumhq.grid:type=Node,name=KubernetesNode",
        description = "Node which creates worker pod per session in kubernetes cluster.")
public class KubernetesNode extends Node {

    private static final Logger LOG = Logger.getLogger(KubernetesNode.class.getName());

    private final EventBus bus;
    private final URI uri;
    private final List<SessionSlot> factories;
    private final int maxSessionCount;
    private final Duration heartbeatPeriod;
    private final Cache<SessionId, SessionSlot> currentSessions;
    private final AtomicInteger pendingSessions = new AtomicInteger();
    private final Route additionalRoutes;

    public KubernetesNode(Tracer tracer, EventBus bus, Secret registrationSecret, NodeId nodeId,
                          URI uri, List<SessionSlot> factories, int maxSessionCount, Duration sessionTimeout,
                          Duration heartbeatPeriod) {
        super(tracer, nodeId, uri, registrationSecret);
        this.bus = bus;
        this.uri = uri;
        this.factories = factories;
        this.maxSessionCount = maxSessionCount;
        this.heartbeatPeriod = heartbeatPeriod;
        this.currentSessions = CacheBuilder.newBuilder()
                .expireAfterAccess(sessionTimeout)
                .ticker(Ticker.systemTicker())
                .removalListener((RemovalListener<SessionId, SessionSlot>) notification -> {
                    LOG.log(Debug.getDebugLogLevel(), "Stopping session {0}", notification.getKey().toString());
                    SessionSlot slot = notification.getValue();
                    if (!slot.isAvailable()) {
                        slot.stop();
                    }
                })
                .build();

        var sessionCleanup = new Regularly("Session Cleanup Node: " + uri);
        sessionCleanup.submit(currentSessions::cleanUp, Duration.ofSeconds(30), Duration.ofSeconds(30));
        var regularHeartBeat = new Regularly("Heartbeat Node: " + uri);
        regularHeartBeat.submit(() -> bus.fire(new NodeHeartBeatEvent(getStatus())), heartbeatPeriod, heartbeatPeriod);

        bus.addListener(SessionClosedEvent.listener(id -> {
            if (this.isDraining() && pendingSessions.decrementAndGet() <= 0) {
                LOG.info("Firing node drain complete message");
                bus.fire(new NodeDrainComplete(this.getId()));
            }
        }));

        additionalRoutes = combine(
                get("/downloads/{sessionId}/{fileName}")
                        .to(params -> new GetFile(getActiveSession(sessionIdFrom(params)), fileNameFrom(params))),
                delete("/downloads/{sessionId}/{fileName}")
                        .to(params -> new DeleteFile(getActiveSession(sessionIdFrom(params)), fileNameFrom(params))),
                get("/downloads/{sessionId}")
                        .to(params -> new ListFiles(getActiveSession(sessionIdFrom(params)))),
                delete("/downloads/{sessionId}")
                        .to(params -> new DeleteFiles(getActiveSession(sessionIdFrom(params)))));

        Runtime.getRuntime().addShutdownHook(new Thread(this::stopAllSessions));
        new JMXHelper().register(this);
    }

    public static Node create(Config config) {
        var loggingOptions = new LoggingOptions(config);
        var eventOptions = new EventBusOptions(config);
        var serverOptions = new BaseServerOptions(config);
        var secretOptions = new SecretOptions(config);
        var networkOptions = new NetworkOptions(config);
        var k8sOptions = new KubernetesOptions(config);

        var tracer = loggingOptions.getTracer();
        var bus = eventOptions.getEventBus();
        var clientFactory = networkOptions.getHttpClientFactory(tracer);
        var k8s = new KubernetesDriver(new DefaultKubernetesClient());
        var factories = createFactories(k8sOptions, tracer, clientFactory, bus, k8s);

        LOG.info("Creating kubernetes node");

        return new KubernetesNode(tracer, bus, secretOptions.getRegistrationSecret(), new NodeId(UUID.randomUUID()),
                serverOptions.getExternalUri(), factories, k8sOptions.getMaxSessions(), k8sOptions.getSessionTimeout(),
                k8sOptions.getHeartbeatPeriod()
        );
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
                            k8sOptions.getVideoImagePullPolicy(), k8sOptions.getVideosPath());
                    return new SessionSlot(eventBus, stereoType, factory);
                }).collect(Collectors.toList());
    }

    @Override
    public Either<WebDriverException, CreateSessionResponse> newSession(CreateSessionRequest sessionRequest) {
        try (var span = tracer.getCurrentContext().createSpan("kubernetes_node.new_session")) {
            Map<String, EventAttributeValue> attributeMap = new HashMap<>();
            attributeMap.put(LOGGER_CLASS.getKey(), setValue(getClass().getName()));
            attributeMap.put("session.request.capabilities", setValue(sessionRequest.getDesiredCapabilities().toString()));
            attributeMap.put("session.request.downstreamdialect", setValue(sessionRequest.getDownstreamDialects()
                    .toString()));

            var currentSessionCount = getCurrentSessionCount();
            span.setAttribute("current.session.count", currentSessionCount);
            attributeMap.put("current.session.count", setValue(currentSessionCount));

            if (getCurrentSessionCount() >= maxSessionCount) {
                span.setAttribute("error", true);
                span.setStatus(Status.RESOURCE_EXHAUSTED);
                attributeMap.put("max.session.count", setValue(maxSessionCount));
                span.addEvent("Max session count reached", attributeMap);
                return Either.left(new RetrySessionRequestException("Max session count reached."));
            }

            SessionSlot slotToUse = null;
            synchronized (factories) {
                for (var factory : factories) {
                    if (!factory.isAvailable() || !factory.test(sessionRequest.getDesiredCapabilities())) {
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

                var isSupportingCdp = slotToUse.isSupportingCdp() || caps.getCapability("se:cdp") != null;
                var externalSession = createExternalSession(session, uri, isSupportingCdp);
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
        return createExternalSession(slot.getSession(), uri, slot.isSupportingCdp());
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

    @Override
    public boolean matches(HttpRequest req) {
        return additionalRoutes.matches(req) || super.matches(req);
    }

    @Override
    public HttpResponse execute(HttpRequest req) {
        return additionalRoutes.matches(req) ? additionalRoutes.execute(req) : super.execute(req);
    }

    SessionId sessionIdFrom(Map<String, String> params) {
        return new SessionId(params.get("sessionId"));
    }

    String fileNameFrom(Map<String, String> params) {
        return params.get("fileName");
    }

    private KubernetesSession getActiveSession(SessionId id) {
        return (KubernetesSession) getSessionSlot(id).getSession();
    }

    private void stopAllSessions() {
        if (currentSessions.size() > 0) {
            LOG.info("Trying to stop all running sessions before shutting down...");
            currentSessions.invalidateAll();
        }
    }

    private Session createExternalSession(ActiveSession other, URI externalUri, boolean isSupportingCdp) {
        Capabilities toUse = ImmutableCapabilities.copyOf(other.getCapabilities());

        if (isSupportingCdp) {
            var cdpPath = String.format("/session/%s/se/cdp", other.getId());
            toUse = new PersistentCapabilities(toUse).setCapability("se:cdp", rewrite(cdpPath));
        }

        boolean isVncEnabled = toUse.getCapability("se:vncLocalAddress") != null;
        if (isVncEnabled) {
            var vncPath = String.format("/session/%s/se/vnc", other.getId());
            toUse = new PersistentCapabilities(toUse).setCapability("se:vnc", rewrite(vncPath));
        }

        return new Session(other.getId(), externalUri, other.getStereotype(), toUse, Instant.now());
    }

    private URI rewrite(String path) {
        try {
            var scheme = "https".equals(uri.getScheme()) ? "wss" : "ws";
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
