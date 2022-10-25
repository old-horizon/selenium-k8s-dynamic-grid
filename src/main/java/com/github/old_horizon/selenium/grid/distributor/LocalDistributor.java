package com.github.old_horizon.selenium.grid.distributor;

import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.grid.distributor.selector.SlotSelector;
import org.openqa.selenium.grid.jmx.ManagedService;
import org.openqa.selenium.grid.security.Secret;
import org.openqa.selenium.grid.sessionmap.SessionMap;
import org.openqa.selenium.grid.sessionqueue.NewSessionQueue;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.tracing.Tracer;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ManagedService(objectName = "org.seleniumhq.grid:type=Distributor,name=LocalDistributor",
        description = "Grid 4 node distributor")
public class LocalDistributor extends org.openqa.selenium.grid.distributor.local.LocalDistributor {

    public LocalDistributor(Tracer tracer,
                            EventBus bus,
                            HttpClient.Factory clientFactory,
                            SessionMap sessions,
                            NewSessionQueue sessionQueue,
                            SlotSelector slotSelector,
                            Secret registrationSecret,
                            Duration healthcheckInterval,
                            boolean rejectUnsupportedCaps,
                            Duration sessionRequestRetryInterval,
                            int newSessionThreadPoolSize) {
        super(tracer, bus, clientFactory, sessions, sessionQueue, slotSelector, registrationSecret, healthcheckInterval,
                rejectUnsupportedCaps, sessionRequestRetryInterval, newSessionThreadPoolSize);
        try {
            var sessionCreatorExecutor = getClass().getSuperclass().getDeclaredField("sessionCreatorExecutor");
            sessionCreatorExecutor.setAccessible(true);
            var executorService = (ExecutorService) sessionCreatorExecutor.get(this);
            executorService.shutdown();
            executorService.shutdownNow();
            sessionCreatorExecutor.set(this, Executors.newCachedThreadPool(r -> {
                var thread = new Thread(r);
                thread.setName("Local Distributor - Session Creation");
                thread.setDaemon(true);
                return thread;
            }));
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to replace sessionCreatorExecutor.", e);
        }
    }
}
