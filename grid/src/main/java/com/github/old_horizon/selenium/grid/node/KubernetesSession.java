package com.github.old_horizon.selenium.grid.node;

import com.github.old_horizon.selenium.k8s.KubernetesDriver;
import com.github.old_horizon.selenium.k8s.PodName;
import dev.failsafe.Failsafe;
import dev.failsafe.RetryPolicy;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.grid.node.DefaultActiveSession;
import org.openqa.selenium.remote.Dialect;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.Tracer;

import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class KubernetesSession extends DefaultActiveSession {

    private final Optional<Path> videosPath;
    private final KubernetesDriver k8s;
    private final PodName podName;
    private final WorkerPodSpec podSpec;

    protected KubernetesSession(Tracer tracer, HttpClient client, SessionId id, URL url, Dialect downstream,
                                Dialect upstream, Capabilities stereotype, Capabilities capabilities,
                                Instant startTime, Optional<Path> videosPath, KubernetesDriver k8s, PodName podName,
                                WorkerPodSpec podSpec) {
        super(tracer, client, id, url, downstream, upstream, stereotype, capabilities, startTime);
        this.videosPath = videosPath;
        this.k8s = k8s;
        this.podName = podName;
        this.podSpec = podSpec;
    }

    @Override
    public void stop() {
        try {
            if (videosPath.isPresent() && podSpec instanceof WorkerPodSpec.VideoRecording) {
                copyRecordedVideo(podName, (WorkerPodSpec.VideoRecording) podSpec, getId());
            }
        } finally {
            k8s.deletePod(podName);
        }
    }

    void copyRecordedVideo(PodName podName, WorkerPodSpec.VideoRecording spec, SessionId sessionId) {
        k8s.executeCommand(podName, spec.getVideoContainerName(), new String[]{"pkill", "-SIGINT", "ffmpeg"});

        var retryPolicy = RetryPolicy.<String>builder()
                .withMaxRetries(-1)
                .withMaxDuration(Duration.ofMinutes(1))
                .withDelay(Duration.ofMillis(500))
                .handleResultIf(v -> !v.trim().isEmpty())
                .build();

        Failsafe.with(retryPolicy).get(() ->
                k8s.executeCommand(podName, spec.getVideoContainerName(), new String[]{"pgrep", "ffmpeg"}));

        k8s.copyFile(podName, spec.getWorkerContainerName(), spec.getVideosPath().resolve("video.mp4"),
                videosPath.get().resolve(sessionId.toString() + ".mp4"));
    }
}
