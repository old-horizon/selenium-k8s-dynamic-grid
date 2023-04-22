package com.github.old_horizon.selenium.k8s;

import java.util.Map;

import static com.github.old_horizon.selenium.k8s.ResourceRequest.Target;
import static com.github.old_horizon.selenium.k8s.ResourceRequest.Value;

public class ResourceRequest implements Map.Entry<Target, Value> {

    private final Target target;
    private final Value value;

    public ResourceRequest(Target target, Value value) {
        this.target = target;
        this.value = value;
    }

    @Override
    public Target getKey() {
        return target;
    }

    @Override
    public Value getValue() {
        return value;
    }

    @Override
    public Value setValue(Value limit) {
        throw new UnsupportedOperationException();
    }

    public enum Target {
        CPU, MEMORY
    }

    public interface Value {
        String getValue();
    }

    public static class Request implements Value {

        private final String value;

        public Request(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }
    }

    public static class Limit implements Value {

        private final String value;

        public Limit(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }
    }
}
