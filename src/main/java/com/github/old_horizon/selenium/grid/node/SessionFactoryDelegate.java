package com.github.old_horizon.selenium.grid.node;

import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.grid.node.ActiveSession;
import org.openqa.selenium.internal.Either;
import org.openqa.selenium.remote.tracing.AttributeKey;
import org.openqa.selenium.remote.tracing.AttributeMap;
import org.openqa.selenium.remote.tracing.Span;
import org.openqa.selenium.remote.tracing.Status;

import java.util.function.Function;
import java.util.logging.Logger;

import static org.openqa.selenium.remote.tracing.Tags.EXCEPTION;

abstract class SessionFactoryDelegate {

    private final Span span;
    private final AttributeMap attributeMap;
    private final Logger log;

    SessionFactoryDelegate(Span span, AttributeMap attributeMap, Logger log) {
        this.span = span;
        this.attributeMap = attributeMap;
        this.log = log;
    }

    abstract Either<WebDriverException, ActiveSession> create(Span span, AttributeMap attributeMap);

    Either<WebDriverException, ActiveSession> webDriverException(Exception e,
                                                                 Function<String, WebDriverException> exceptionFunction,
                                                                 String message) {
        span.setAttribute("error", true);
        span.setStatus(Status.CANCELLED);
        EXCEPTION.accept(attributeMap, e);
        attributeMap.put(AttributeKey.EXCEPTION_MESSAGE.getKey(), message);
        span.addEvent(AttributeKey.EXCEPTION_EVENT.getKey(), attributeMap);
        log.warning(message);
        return Either.left(exceptionFunction.apply(message));
    }

    Either<WebDriverException, ActiveSession> execute() {
        try (var span = this.span) {
            return create(span, attributeMap);
        } catch (Exception e) {
            return Either.left(new SessionNotCreatedException(e.getMessage()));
        }
    }
}
