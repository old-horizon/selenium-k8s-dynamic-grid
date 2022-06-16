package com.github.old_horizon.selenium.grid.commands;

import com.google.common.collect.ImmutableMap;
import org.openqa.selenium.grid.config.MapConfig;

class DefaultDynamicGridConfig extends MapConfig {
    DefaultDynamicGridConfig() {
        super(ImmutableMap.of(
                "events", ImmutableMap.of(
                        "implementation", "org.openqa.selenium.events.local.GuavaEventBus"),
                "sessions", ImmutableMap.of(
                        "implementation", "org.openqa.selenium.grid.sessionmap.local.LocalSessionMap")));
    }
}
