package com.sparrowwallet.frigate.io;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.spi.FilterReply;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcError;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JsonRpcErrorFilterTest {
    @Test
    public void filterRegisteredFromLogbackConfig() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        assertTrue(ctx.getTurboFilterList().stream().anyMatch(f -> f instanceof JsonRpcErrorFilter), "JsonRpcErrorFilter must be loaded from logback.xml");
    }

    @Test
    public void deniesAnnotatedRootCause() {
        JsonRpcErrorFilter filter = new JsonRpcErrorFilter();
        Logger logger = (Logger) LoggerFactory.getLogger("com.github.arteam.simplejsonrpc.server.JsonRpcServer");
        Throwable cause = new AnnotatedException();
        Throwable wrapped = new RuntimeException(cause);
        assertEquals(FilterReply.DENY, filter.decide(null, logger, Level.ERROR, "Error while processing", null, wrapped));
    }

    @Test
    public void neutralWhenNoAnnotation() {
        JsonRpcErrorFilter filter = new JsonRpcErrorFilter();
        Logger logger = (Logger) LoggerFactory.getLogger("com.github.arteam.simplejsonrpc.server.JsonRpcServer");
        Throwable wrapped = new RuntimeException(new IllegalStateException("boom"));
        assertEquals(FilterReply.NEUTRAL, filter.decide(null, logger, Level.ERROR, "Error while processing", null, wrapped));
    }

    @Test
    public void neutralForOtherLoggers() {
        JsonRpcErrorFilter filter = new JsonRpcErrorFilter();
        Logger logger = (Logger) LoggerFactory.getLogger("some.other.Logger");
        assertEquals(FilterReply.NEUTRAL, filter.decide(null, logger, Level.ERROR, "msg", null, new AnnotatedException()));
    }

    @JsonRpcError(code = -1, message = "test")
    private static class AnnotatedException extends Exception {
    }
}
