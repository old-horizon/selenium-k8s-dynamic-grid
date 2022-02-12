package com.github.old_horizon.selenium.k8s;

import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class KubernetesDriver {

    private final KubernetesClient client;

    public KubernetesDriver(KubernetesClient client) {
        this.client = client;
    }

    public OwnerReference getOwnerReference(PodName name) {
        return toOwnerReference(client.pods().withName(name.getValue()).get());
    }

    public PodName createPod(PodSpec spec) {
        var pod = client.pods().create(spec.build());
        return new PodName(pod.getMetadata().getName());
    }

    public Ip getPodIp(PodName name) {
        var retryPolicy = new RetryPolicy<String>()
                .withMaxRetries(-1)
                .withMaxDuration(Duration.ofMinutes(1))
                .withDelay(Duration.ofMillis(500))
                .handleResultIf(Objects::isNull);

        var ip = Failsafe.with(retryPolicy).get(() ->
                client.pods().withName(name.getValue()).get().getStatus().getPodIP());
        return new Ip(ip);
    }

    public String executeCommand(PodName podName, ContainerName containerName, String[] commands) {
        var latch = new CountDownLatch(1);
        try (var out = new ByteArrayOutputStream();
             var ignore = client.pods().withName(podName.getValue()).inContainer(containerName.getValue())
                     .writingOutput(out).usingListener(commandExecListener(latch, commands))
                     .exec(commands)) {
            latch.await(5, TimeUnit.SECONDS);
            return out.toString();
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public InputStream getFile(PodName podName, ContainerName containerName, Path path) {
        return client.pods().withName(podName.getValue()).inContainer(containerName.getValue()).file(path.toString())
                .read();
    }

    public void copyFile(PodName podName, ContainerName containerName, Path source, Path destination) {
        client.pods().withName(podName.getValue()).inContainer(containerName.getValue()).file(source.toString())
                .copy(destination);
    }

    public void deletePod(PodName name) {
        client.pods().withName(name.getValue()).delete();
    }

    OwnerReference toOwnerReference(io.fabric8.kubernetes.api.model.Pod owner) {
        var metadata = owner.getMetadata();
        return new OwnerReference(owner.getApiVersion(), false, true, owner.getKind(),
                metadata.getName(), metadata.getUid());
    }

    private static ExecListener commandExecListener(CountDownLatch latch, String[] commands) {
        return new ExecListener() {
            @Override
            public void onFailure(Throwable t, Response failureResponse) {
                latch.countDown();
                throw new UncheckedIOException("Failed to execute command: " + Arrays.toString(commands),
                        new IOException(t));
            }

            @Override
            public void onClose(int code, String reason) {
                latch.countDown();
            }
        };
    }
}
