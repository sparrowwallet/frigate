package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcError;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcErrorData;

import java.io.IOException;

/**
 * Thrown by the backend transport when a request cannot be completed: the connection is not established, was lost, or the
 * request timed out. The JSON-RPC client wraps it, and the server reports the root cause to the client as this error.
 */
@JsonRpcError(code=-32000, message="Backend Electrum server unavailable")
public class BackendUnavailableException extends IOException {
    @JsonRpcErrorData
    private final String reason;

    public BackendUnavailableException(String reason) {
        super("Backend Electrum server unavailable: " + reason);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
