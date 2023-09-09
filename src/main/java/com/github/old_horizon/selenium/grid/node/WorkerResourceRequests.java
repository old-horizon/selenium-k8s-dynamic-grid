package com.github.old_horizon.selenium.grid.node;

import com.github.old_horizon.selenium.k8s.ResourceRequest;
import com.github.old_horizon.selenium.k8s.ResourceRequests;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class WorkerResourceRequests extends ResourceRequests {

    private final Set<ResourceRequest> entries;

    WorkerResourceRequests(Optional<String> cpuRequest, Optional<String> cpuLimit, Optional<String> memoryRequest,
                           Optional<String> memoryLimit) {
        entries = Stream.of(
                        request(ResourceRequest.Target.CPU, cpuRequest),
                        limit(ResourceRequest.Target.CPU, cpuLimit),
                        request(ResourceRequest.Target.MEMORY, memoryRequest),
                        limit(ResourceRequest.Target.MEMORY, memoryLimit))
                .filter(Optional::isPresent).map(Optional::get).collect(Collectors.toSet());
    }

    @Override
    public Set<Entry<ResourceRequest.Target, ResourceRequest.Value>> entrySet() {
        return Set.copyOf(entries);
    }

    private Optional<ResourceRequest> request(ResourceRequest.Target target, Optional<String> value) {
        return value.map(v -> new ResourceRequest(target, new ResourceRequest.Request(v)));
    }

    private Optional<ResourceRequest> limit(ResourceRequest.Target target, Optional<String> value) {
        return value.map(v -> new ResourceRequest(target, new ResourceRequest.Limit(v)));
    }
}
