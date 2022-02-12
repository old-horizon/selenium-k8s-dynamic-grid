package com.github.old_horizon.selenium.grid.hub;

import com.github.old_horizon.selenium.grid.hub.downloads.DeleteFile;
import com.github.old_horizon.selenium.grid.hub.downloads.DeleteFiles;
import com.github.old_horizon.selenium.grid.hub.downloads.GetFile;
import com.github.old_horizon.selenium.grid.hub.downloads.ListFiles;
import com.google.auto.service.AutoService;
import org.openqa.selenium.cli.CliCommand;
import org.openqa.selenium.grid.commands.Hub;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.Routable;

import java.util.Map;

import static org.openqa.selenium.remote.http.Route.*;

@AutoService(CliCommand.class)
public class DynamicGridHub extends Hub {
    @Override
    public String getName() {
        return "dynamic-grid-hub";
    }

    @Override
    protected Handlers createHandlers(Config config) {
        var handlers = super.createHandlers(config);
        var httpHandler = (Routable) handlers.httpHandler;
        return new Handlers(
                combine(httpHandler,
                        get("/downloads/{sessionId}/{fileName}")
                                .to(params -> new GetFile(httpHandler, sessionIdFrom(params), fileNameFrom(params))),
                        delete("/downloads/{sessionId}/{fileName}")
                                .to(params -> new DeleteFile(httpHandler, sessionIdFrom(params), fileNameFrom(params))),
                        get("/downloads/{sessionId}")
                                .to(params -> new ListFiles(httpHandler, sessionIdFrom(params))),
                        delete("/downloads/{sessionId}")
                                .to(params -> new DeleteFiles(httpHandler, sessionIdFrom(params)))),
                handlers.websocketHandler);
    }

    SessionId sessionIdFrom(Map<String, String> params) {
        return new SessionId(params.get("sessionId"));
    }

    String fileNameFrom(Map<String, String> params) {
        return params.get("fileName");
    }
}
