package com.sparrowwallet.frigate.cli;

import com.github.arteam.simplejsonrpc.client.Transport;
import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.frigate.io.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

import static com.sparrowwallet.frigate.electrum.ElectrumServerRunnable.DEFAULT_PORT;

public class ElectrumTransport implements Transport {
    private static final Logger log = LoggerFactory.getLogger(ElectrumTransport.class);

    private final HostAndPort electrumServer;
    private Socket socket;

    public ElectrumTransport(HostAndPort electrumServer) {
        this.electrumServer = electrumServer;
        try {
            String host = electrumServer.getHost();
            int port = electrumServer.hasPort() ? electrumServer.getPort() : DEFAULT_PORT;

            SocketFactory socketFactory = SocketFactory.getDefault();
            this.socket = socketFactory.createSocket(host, port);
        } catch(UnknownHostException e) {
            log.error("Unknown host: " + electrumServer.getHost());
        } catch(IOException e) {
            log.error("Error connecting to Electrum server: " + electrumServer.getHost());
        }
    }

    @Override
    public String pass(String request) throws IOException {
        PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)));
        out.println(request);
        out.flush();

        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        return in.readLine();
    }
}
