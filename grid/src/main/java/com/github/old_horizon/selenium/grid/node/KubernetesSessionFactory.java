package com.github.old_horizon.selenium.grid.node;

import com.github.old_horizon.selenium.k8s.*;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import io.fabric8.kubernetes.api.model.OwnerReference;
import org.openqa.selenium.*;
import org.openqa.selenium.grid.data.CreateSessionRequest;
import org.openqa.selenium.grid.data.DefaultSlotMatcher;
import org.openqa.selenium.grid.data.SlotMatcher;
import org.openqa.selenium.grid.node.ActiveSession;
import org.openqa.selenium.grid.node.SessionFactory;
import org.openqa.selenium.internal.Either;
import org.openqa.selenium.net.HostIdentifier;
import org.openqa.selenium.remote.*;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.tracing.EventAttribute;
import org.openqa.selenium.remote.tracing.EventAttributeValue;
import org.openqa.selenium.remote.tracing.Span;
import org.openqa.selenium.remote.tracing.Tracer;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.openqa.selenium.remote.Dialect.W3C;
import static org.openqa.selenium.remote.http.Contents.string;
import static org.openqa.selenium.remote.http.HttpMethod.GET;
import static org.openqa.selenium.remote.tracing.AttributeKey.*;
import static org.openqa.selenium.remote.tracing.EventAttribute.setValue;

public class KubernetesSessionFactory implements SessionFactory {

    private static final Logger LOG = Logger.getLogger(KubernetesSessionFactory.class.getName());

    private final Tracer tracer;
    private final HttpClient.Factory clientFactory;
    private final KubernetesDriver k8s;
    private final Duration workerStartupTimeout;
    private final ImagePullPolicy workerImagePullPolicy;
    private final ResourceRequests resourceRequests;
    private final DockerImage workerImage;
    private final Capabilities stereoType;
    private final DockerImage videoImage;
    private final Duration videoStartupTimeout;
    private final ImagePullPolicy videoImagePullPolicy;
    private final Optional<Path> videosPath;
    private final SlotMatcher slotMatcher;

    public KubernetesSessionFactory(Tracer tracer, HttpClient.Factory clientFactory, KubernetesDriver k8s,
                                    Duration workerStartupTimeout, ResourceRequests resourceRequests,
                                    DockerImage workerImage, ImagePullPolicy workerImagePullPolicy,
                                    Capabilities stereoType, DockerImage videoImage, Duration videoStartupTimeout,
                                    ImagePullPolicy videoImagePullPolicy, Optional<Path> videosPath) {
        this.tracer = tracer;
        this.clientFactory = clientFactory;
        this.k8s = k8s;
        this.workerStartupTimeout = workerStartupTimeout;
        this.resourceRequests = resourceRequests;
        this.workerImage = workerImage;
        this.workerImagePullPolicy = workerImagePullPolicy;
        this.stereoType = stereoType;
        this.videoImage = videoImage;
        this.videoStartupTimeout = videoStartupTimeout;
        this.videoImagePullPolicy = videoImagePullPolicy;
        this.videosPath = videosPath;
        this.slotMatcher = new DefaultSlotMatcher();
    }

    @Override
    public Either<WebDriverException, ActiveSession> apply(CreateSessionRequest sessionRequest) {
        var desiredCapabilities = sessionRequest.getDesiredCapabilities();
        LOG.info("Starting session for " + desiredCapabilities);
        return new SessionFactoryDelegate(tracer.getCurrentContext()
                .createSpan("kubernetes_session_factory.apply"), new HashMap<>(), LOG) {
            @Override
            Either<WebDriverException, ActiveSession> create(Span span, Map<String, EventAttributeValue> attributeMap) {
                attributeMap.put(LOGGER_CLASS.getKey(), setValue(this.getClass().getName()));
                LOG.info("Creating worker pod...");
                WorkerPodSpec podSpec = getWorkerPodSpec(desiredCapabilities);
                var podName = k8s.createPod(podSpec);
                var podIp = k8s.getPodIp(podName);
                var workerPort = podSpec.getWorkerPort();

                LOG.info(String.format("Waiting for worker to start (pod: %s)", podName));

                HttpClient client;
                URL remoteAddress;
                try {
                    remoteAddress = toUrl(String.format("http://%s:%d/wd/hub", podIp, workerPort));
                    client = clientFactory.createClient(remoteAddress);
                    waitForServerToStart(client, workerStartupTimeout);

                    if (podSpec instanceof WorkerPodSpec.VideoRecording) {
                        try (var videoClient = clientFactory.createClient(toUrl(String.format("http://%s:%d",
                                podIp, ((WorkerPodSpec.VideoRecording) podSpec).getVideoPort())))) {
                            waitForServerToStart(videoClient, videoStartupTimeout);
                        }
                    }
                } catch (Exception e) {
                    k8s.deletePod(podName);
                    return webDriverException(e, RetrySessionRequestException::new, "Unable to connect to worker.");
                }
                LOG.info(String.format("Worker is ready (pod: %s)", podName));

                var command = new Command(null, DriverCommand.NEW_SESSION(desiredCapabilities));
                ProtocolHandshake.Result result;
                Response response;
                try {
                    result = new ProtocolHandshake().createSession(client, command);
                    response = result.createResponse();
                } catch (IOException | RuntimeException e) {
                    k8s.deletePod(podName);
                    return webDriverException(e, SessionNotCreatedException::new,
                            "Unable to create session: " + e.getMessage());
                }

                var id = new SessionId(response.getSessionId());
                var capabilities = new ImmutableCapabilities((Map<?, ?>) response.getValue());
                var mergedCapabilities = capabilities.merge(desiredCapabilities);
                mergedCapabilities = addForwardCdpEndpoint(mergedCapabilities, podIp, workerPort, id.toString());
                var dialect = result.getDialect();
                var downstream = sessionRequest.getDownstreamDialects().contains(dialect) ? dialect : W3C;
                attributeMap.put(DOWNSTREAM_DIALECT.getKey(), EventAttribute.setValue(downstream.toString()));
                attributeMap.put(DRIVER_RESPONSE.getKey(), EventAttribute.setValue(response.toString()));

                span.addEvent("Kubernetes driver service created session", attributeMap);
                LOG.fine(String.format("Created session: %s - %s (pod: %s)", id, capabilities, podName));
                return Either.right(new KubernetesSession(tracer, client, id, remoteAddress, downstream, dialect,
                        stereoType, mergedCapabilities, Instant.now(), videosPath, k8s, podName, podSpec));
            }
        }.execute();
    }

    @Override
    public boolean test(Capabilities capabilities) {
        return slotMatcher.matches(stereoType, capabilities);
    }

    WorkerPodSpec getWorkerPodSpec(Capabilities desiredCapabilities) {
        var screenResolution = getScreenResolution(desiredCapabilities);
        var timeZone = getTimeZone(desiredCapabilities);
        var envVars = getEnvVars();
        return recordVideo(desiredCapabilities) ?
                new WorkerPodSpec.VideoRecording(workerImage, workerImagePullPolicy, videoImage, videoImagePullPolicy,
                        resourceRequests, screenResolution, timeZone, envVars, getSelfReference()) :
                new WorkerPodSpec.Default(workerImage, workerImagePullPolicy, resourceRequests, screenResolution,
                        timeZone, envVars, getSelfReference());
    }

    Optional<Dimension> getScreenResolution(Capabilities desiredCapabilities) {
        try {
            return Optional.ofNullable(desiredCapabilities.getCapability("se:screenResolution"))
                    .map(r -> {
                        var resolution = Arrays.stream(r.toString().split("x"))
                                .mapToInt(Integer::parseInt)
                                .filter(v -> v > 0)
                                .toArray();
                        if (resolution.length == 2) {
                            return new Dimension(resolution[0], resolution[1]);
                        }
                        throw new IllegalArgumentException(
                                "Illegal screen resolution specified: " + Arrays.toString(resolution));
                    });
        } catch (Exception e) {
            LOG.warning("Unable to configure screen resolution: " + e.getMessage());
            return Optional.empty();
        }
    }

    Optional<TimeZone> getTimeZone(Capabilities desiredCapabilities) {
        return Arrays.stream(TimeZone.getAvailableIDs()).filter(tz -> tz.equals(
                        Optional.ofNullable(desiredCapabilities.getCapability("se:timeZone"))
                                .orElseGet(() -> System.getenv("TZ"))))
                .findFirst()
                .map(TimeZone::getTimeZone);
    }

    Map<String, String> getEnvVars() {
        return System.getenv().entrySet().stream().filter(
                        e -> e.getKey().startsWith("SE_") || e.getKey().equalsIgnoreCase("LANGUAGE"))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    boolean recordVideo(Capabilities desiredCapabilities) {
        return Optional.ofNullable(desiredCapabilities.getCapability("se:recordVideo"))
                .map(Object::toString)
                .map(Boolean::parseBoolean)
                .orElse(false);
    }

    OwnerReference getSelfReference() {
        var myName = new PodName(HostIdentifier.getHostName());
        return k8s.getOwnerReference(myName);
    }

    URL toUrl(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    void waitForServerToStart(HttpClient client, Duration duration) {
        var retryPolicy = RetryPolicy.<Integer>builder()
                .withMaxRetries(-1)
                .withMaxDuration(duration)
                .withDelay(5, 10, ChronoUnit.SECONDS)
                .handleResultIf(status -> status != 200)
                .build();

        Failsafe.with(retryPolicy).get(() -> {
            var response = client.execute(new HttpRequest(GET, "/status"));
            LOG.fine(string(response));
            return response.getStatus();
        });
    }

    Capabilities addForwardCdpEndpoint(Capabilities sessionCapabilities, Ip ip, int port, String sessionId) {
        var forwardCdpPath = String.format("ws://%s:%s/session/%s/se/fwd", ip, port, sessionId);
        return new PersistentCapabilities(sessionCapabilities).setCapability("se:forwardCdp", forwardCdpPath);
    }
}
