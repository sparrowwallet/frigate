package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.client.Transport;

import java.io.IOException;

public class ElectrumNotificationTransport implements Transport {
    private final RequestHandler requestHandler;

    public ElectrumNotificationTransport(RequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    @Override
    public String pass(String request) throws IOException {
        requestHandler.writeLine(request);
        return "{\"result\":{},\"error\":null,\"id\":1}";
    }
}
