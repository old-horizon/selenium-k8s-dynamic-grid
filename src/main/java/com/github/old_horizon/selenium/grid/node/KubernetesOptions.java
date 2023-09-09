package com.github.old_horizon.selenium.grid.node;

import com.github.old_horizon.selenium.k8s.DockerImage;
import com.github.old_horizon.selenium.k8s.ImagePullPolicy;
import com.google.common.collect.Iterables;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.json.Json;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class KubernetesOptions {

    private static final String SECTION_NAME = "kubernetes";
    private static final String DEFAULT_VIDEO_IMAGE = "selenium/video:latest";
    private static final int DEFAULT_MAX_SESSIONS = Runtime.getRuntime().availableProcessors();
    private static final Json JSON = new Json();

    private final org.openqa.selenium.grid.config.Config config;

    public KubernetesOptions(org.openqa.selenium.grid.config.Config config) {
        this.config = config;
    }

    public List<Config> getConfigs() {
        return getAll("configs")
                .map(configs -> StreamSupport.stream(Iterables.partition(configs, 2).spliterator(), false)
                        .map(config -> {
                            var image = new DockerImage(config.get(0));
                            Capabilities stereoType = JSON.toType(config.get(1), Capabilities.class);
                            return new Config(image, stereoType);
                        }).collect(Collectors.toList())).orElse(Collections.emptyList());
    }

    public DockerImage getVideoImage() {
        return new DockerImage(get("video-image").orElse(DEFAULT_VIDEO_IMAGE));
    }

    public Duration getVideoStartupTimeout() {
        return getInt("video-startup-timeout").map(Duration::ofSeconds).orElseGet(() -> Duration.ofMinutes(1));
    }

    public ImagePullPolicy getVideoImagePullPolicy() {
        try {
            return get("video-image-pull-policy").map(ImagePullPolicy::valueOf).orElse(ImagePullPolicy.Always);
        } catch (Exception e) {
            return ImagePullPolicy.Always;
        }
    }

    public Optional<Path> getVideosPath() {
        return get("videos-path").map(Path::of);
    }

    public int getMaxSessions() {
        return getInt("max-sessions").orElse(DEFAULT_MAX_SESSIONS);
    }

    public Duration getWorkerStartupTimeout() {
        return getInt("worker-startup-timeout").map(Duration::ofSeconds).orElseGet(() -> Duration.ofMinutes(1));
    }

    public ImagePullPolicy getWorkerImagePullPolicy() {
        try {
            return get("worker-image-pull-policy").map(ImagePullPolicy::valueOf).orElse(ImagePullPolicy.Always);
        } catch (Exception e) {
            return ImagePullPolicy.Always;
        }
    }

    public WorkerResourceRequests getWorkerResourceRequests() {
        return new WorkerResourceRequests(get("worker-cpu-request"), get("worker-cpu-limit"),
                get("worker-memory-request"), get("worker-memory-limit"));
    }

    public Duration getSessionTimeout() {
        var seconds = Math.max(getInt("session-timeout").orElse(300), 10);
        return Duration.ofSeconds(seconds);
    }

    public Duration getHeartbeatPeriod() {
        var seconds = Math.max(getInt("heartbeat-period").orElse(60), 1);
        return Duration.ofSeconds(seconds);
    }

    private Optional<String> get(String option) {
        return config.get(SECTION_NAME, option);
    }

    private Optional<Integer> getInt(String option) {
        return config.getInt(SECTION_NAME, option);
    }

    private Optional<List<String>> getAll(String option) {
        return config.getAll(SECTION_NAME, option);
    }

    public static class Config {

        private final DockerImage image;
        private final Capabilities stereoType;

        public Config(DockerImage image, Capabilities stereoType) {
            this.image = image;
            this.stereoType = stereoType;
        }

        public DockerImage getImage() {
            return image;
        }

        public Capabilities getStereoType() {
            return stereoType;
        }
    }

}
