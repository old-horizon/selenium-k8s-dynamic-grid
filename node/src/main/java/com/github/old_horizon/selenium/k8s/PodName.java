package com.github.old_horizon.selenium.k8s;

import java.util.Objects;

public class PodName {

    private final String value;

    public PodName(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PodName podName = (PodName) o;
        return Objects.equals(value, podName.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
