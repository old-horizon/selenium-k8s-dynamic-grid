package com.github.old_horizon.selenium.k8s;

import io.fabric8.kubernetes.api.model.Pod;

public interface PodSpec {
    Pod build();
}
