package com.sparrowwallet.frigate.io;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcError;
import org.slf4j.Marker;

public class JsonRpcErrorFilter extends TurboFilter {
    private static final String JSON_RPC_SERVER_LOGGER = "com.github.arteam.simplejsonrpc.server.JsonRpcServer";

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable t) {
        if(level == Level.ERROR && t != null && JSON_RPC_SERVER_LOGGER.equals(logger.getName()) && rootCauseHasJsonRpcError(t)) {
            return FilterReply.DENY;
        }
        return FilterReply.NEUTRAL;
    }

    private static boolean rootCauseHasJsonRpcError(Throwable t) {
        Throwable cause = t;
        while(cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().isAnnotationPresent(JsonRpcError.class);
    }
}
