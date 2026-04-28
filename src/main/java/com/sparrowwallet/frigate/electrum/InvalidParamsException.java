package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcError;

@JsonRpcError(code = -32602, message = "Invalid params")
public class InvalidParamsException extends Exception {
    public InvalidParamsException(String message) {
        super(message);
    }

    public InvalidParamsException(String message, Throwable cause) {
        super(message, cause);
    }
}
