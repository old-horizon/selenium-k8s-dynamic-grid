package com.github.old_horizon.selenium.grid.node;

import com.github.old_horizon.selenium.k8s.PodSpec;
import com.github.old_horizon.selenium.k8s.*;
import io.fabric8.kubernetes.api.model.*;
import org.openqa.selenium.Dimension;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;

abstract class WorkerPodSpec implements PodSpec {

    static final ContainerName WORKER_CONTAINER_NAME = new ContainerName("worker");
    static final String DSHM_VOLUME_NAME = "dshm";

    private static final int WORKER_PORT = 4444;

    final ImagePullPolicy imagePullPolicy;
    final DockerImage image;
    final ResourceRequests resourceRequests;
    final Optional<Dimension> screenResolution;

    private final Optional<TimeZone> timeZone;
    private Map<String, String> envVars;
    private final OwnerReference owner;

    WorkerPodSpec(DockerImage image, ImagePullPolicy imagePullPolicy, ResourceRequests resourceRequests,
                  Optional<Dimension> screenResolution, Optional<TimeZone> timeZone, Map<String, String> envVars,
                  OwnerReference owner) {
        this.imagePullPolicy = imagePullPolicy;
        this.image = image;
        this.resourceRequests = resourceRequests;
        this.screenResolution = screenResolution;
        this.timeZone = timeZone;
        this.envVars = envVars;
        this.owner = owner;
    }

    int getWorkerPort() {
        return WORKER_PORT;
    }

    ContainerName getWorkerContainerName() {
        return WORKER_CONTAINER_NAME;
    }

    void addSharedMemoryVolume(PodFluent<PodBuilder>.SpecNested<PodBuilder> spec) {
        // @formatter:off
        spec.addNewVolume()
                .withName(DSHM_VOLUME_NAME)
                .withNewEmptyDir()
                .withMedium("Memory")
                .endEmptyDir()
                .endVolume();
        // @formatter:on
    }

    void applyResourceRequests(PodSpecFluent<PodFluent<PodBuilder>.SpecNested<PodBuilder>>
                                       .ContainersNested<PodFluent<PodBuilder>.SpecNested<PodBuilder>> spec) {
        var requests = new HashMap<String, Quantity>();
        var limits = new HashMap<String, Quantity>();

        resourceRequests.forEach((target, value) -> {
            var key = target.name().toLowerCase();
            if (value instanceof ResourceRequest.Request) {
                requests.put(key, new Quantity(value.getValue()));
            } else if (value instanceof ResourceRequest.Limit) {
                limits.put(key, new Quantity(value.getValue()));
            } else {
                throw new AssertionError();
            }
        });

        var requirements = new ResourceRequirements();
        requirements.setRequests(requests);
        requirements.setLimits(limits);
        spec.withResources(requirements);
    }

    void setWorkerScreenResolution(PodSpecFluent<PodFluent<PodBuilder>.SpecNested<PodBuilder>>
                                           .ContainersNested<PodFluent<PodBuilder>.SpecNested<PodBuilder>> spec) {
        if (screenResolution.isPresent()) {
            var sr = screenResolution.get();
            // @formatter:off
            spec.addNewEnv()
                    .withName("SE_SCREEN_WIDTH")
                    .withValue(Integer.toString(sr.getWidth()))
                .endEnv()
                .addNewEnv()
                    .withName("SE_SCREEN_HEIGHT")
                    .withValue(Integer.toString(sr.getHeight()))
                .endEnv();
            // @formatter:on
        }
    }

    void setTimeZone(PodSpecFluent<PodFluent<PodBuilder>.SpecNested<PodBuilder>>
                             .ContainersNested<PodFluent<PodBuilder>.SpecNested<PodBuilder>> spec) {
        if (timeZone.isPresent()) {
            var tz = timeZone.get();
            // @formatter:off
            spec.addNewEnv()
                    .withName("TZ")
                    .withValue(tz.getID())
                .endEnv();
            // @formatter:on
        }
    }

    void setEnvVars(PodSpecFluent<PodFluent<PodBuilder>.SpecNested<PodBuilder>>
                            .ContainersNested<PodFluent<PodBuilder>.SpecNested<PodBuilder>> spec) {
        envVars.forEach((k, v) -> {
            // @formatter:off
            spec.addNewEnv()
                    .withName(k)
                    .withValue(v)
                .endEnv();
            // @formatter:on
        });
    }

    abstract void customize(PodFluent<PodBuilder>.SpecNested<PodBuilder> spec);

    @Override
    public Pod build() {
        // @formatter:off
        var spec = new PodBuilder()
                .withNewMetadata()
                    .withGenerateName("worker-")
                    .withOwnerReferences(owner)
                .endMetadata()
                .withNewSpec();
        // @formatter:on
        customize(spec);
        spec.withRestartPolicy("Never");
        return spec.endSpec().build();
    }

    static class Default extends WorkerPodSpec {

        Default(DockerImage image, ImagePullPolicy imagePullPolicy, ResourceRequests resourceRequests,
                Optional<Dimension> screenResolution, Optional<TimeZone> timeZone, Map<String, String> envVars,
                OwnerReference owner) {
            super(image, imagePullPolicy, resourceRequests, screenResolution, timeZone, envVars, owner);
        }

        @Override
        void customize(PodFluent<PodBuilder>.SpecNested<PodBuilder> spec) {
            addSharedMemoryVolume(spec);
            addWorkerContainer(spec);
        }

        void addWorkerContainer(PodFluent<PodBuilder>.SpecNested<PodBuilder> spec) {
            // @formatter:off
            var containerSpec = spec.addNewContainer()
                                    .withName(WORKER_CONTAINER_NAME.getValue())
                                    .withImage(image.getValue())
                                    .withImagePullPolicy(imagePullPolicy.name())
                                    .addNewVolumeMount()
                                        .withName(DSHM_VOLUME_NAME)
                                        .withMountPath("/dev/shm")
                                    .endVolumeMount()
                                    .addNewEnv()
                                        .withName("VNC_NO_PASSWORD")
                                        .withValue("1")
                                    .endEnv();
            // @formatter:on
            applyResourceRequests(containerSpec);
            setWorkerScreenResolution(containerSpec);
            setTimeZone(containerSpec);
            setEnvVars(containerSpec);
            containerSpec.endContainer();
        }
    }

    static class VideoRecording extends WorkerPodSpec {

        private static final ContainerName VIDEO_CONTAINER_NAME = new ContainerName("video");
        private static final Path VIDEOS_PATH = Path.of("/videos");
        private static final String VIDEOS_VOLUME_NAME = "videos";
        private static final int VIDEO_PORT = 9000;

        private final DockerImage videoImage;
        private final ImagePullPolicy videoImagePullPolicy;

        VideoRecording(DockerImage workerImage, ImagePullPolicy workerImagePullPolicy, DockerImage videoImage,
                       ImagePullPolicy videoImagePullPolicy, ResourceRequests resourceRequests,
                       Optional<Dimension> screenResolution, Optional<TimeZone> timeZone, Map<String, String> envVars,
                       OwnerReference owner) {
            super(workerImage, workerImagePullPolicy, resourceRequests, screenResolution, timeZone, envVars, owner);
            this.videoImage = videoImage;
            this.videoImagePullPolicy = videoImagePullPolicy;
        }

        int getVideoPort() {
            return VIDEO_PORT;
        }

        ContainerName getVideoContainerName() {
            return VIDEO_CONTAINER_NAME;
        }

        Path getVideosPath() {
            return VIDEOS_PATH;
        }

        @Override
        void customize(PodFluent<PodBuilder>.SpecNested<PodBuilder> spec) {
            addSharedMemoryVolume(spec);
            addVideosVolume(spec);
            addWorkerContainer(spec);
            addVideoContainer(spec);
        }

        void addWorkerContainer(PodFluent<PodBuilder>.SpecNested<PodBuilder> spec) {
            // @formatter:off
            var containerSpec = spec.addNewContainer()
                                    .withName(WORKER_CONTAINER_NAME.getValue())
                                    .withImage(image.getValue())
                                    .withImagePullPolicy(imagePullPolicy.name())
                                    .addNewVolumeMount()
                                        .withName(DSHM_VOLUME_NAME)
                                        .withMountPath("/dev/shm")
                                    .endVolumeMount()
                                    .addNewVolumeMount()
                                        .withName(VIDEOS_VOLUME_NAME)
                                        .withMountPath(VIDEOS_PATH.toString())
                                    .endVolumeMount()
                                    .addNewEnv()
                                        .withName("VNC_NO_PASSWORD")
                                        .withValue("1")
                                    .endEnv();
            // @formatter:on
            applyResourceRequests(containerSpec);
            setWorkerScreenResolution(containerSpec);
            setTimeZone(containerSpec);
            setEnvVars(containerSpec);
            containerSpec.endContainer();
        }

        void addVideosVolume(PodFluent<PodBuilder>.SpecNested<PodBuilder> spec) {
            // @formatter:off
            spec.addNewVolume()
                    .withName(VIDEOS_VOLUME_NAME)
                    .withNewEmptyDir()
                    .endEmptyDir()
                .endVolume();
            // @formatter:on
        }

        void addVideoContainer(PodFluent<PodBuilder>.SpecNested<PodBuilder> spec) {
            // @formatter:off
            var containerSpec = spec.addNewContainer()
                                    .withName(VIDEO_CONTAINER_NAME.getValue())
                                    .withImage(videoImage.getValue())
                                    .withImagePullPolicy(videoImagePullPolicy.name())
                                    .addNewVolumeMount()
                                        .withName(VIDEOS_VOLUME_NAME)
                                        .withMountPath(VIDEOS_PATH.toString())
                                    .endVolumeMount()
                                    .addNewEnv()
                                        .withName("DISPLAY_CONTAINER_NAME")
                                        .withValue("localhost")
                                    .endEnv();
            // @formatter:on
            if (screenResolution.isPresent()) {
                var sr = screenResolution.get();
                // @formatter:off
                containerSpec.addNewEnv()
                            .withName("SE_SCREEN_WIDTH")
                            .withValue(Integer.toString(sr.getWidth()))
                        .endEnv()
                        .addNewEnv()
                            .withName("SE_SCREEN_HEIGHT")
                            .withValue(Integer.toString(sr.getHeight()))
                        .endEnv();
                // @formatter:on
            }
            containerSpec.endContainer();
        }
    }
}
