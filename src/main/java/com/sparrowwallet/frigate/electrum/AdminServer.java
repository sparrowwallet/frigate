package com.sparrowwallet.frigate.electrum;

import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcMethod;
import com.github.arteam.simplejsonrpc.core.annotation.JsonRpcService;
import com.github.arteam.simplejsonrpc.server.JsonRpcServer;
import com.sparrowwallet.frigate.io.BoundedLineReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * A local admin endpoint for operator tooling, such as health checks from monitoring scripts. It speaks the same newline-delimited
 * JSON-RPC as the Electrum port, with a single method, getinfo. It is bound to the loopback address whatever the configuration, as
 * Fulcrum's admin RPC is, and deliberately offers nothing per session: see AdminInfo.
 */
public class AdminServer implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(AdminServer.class);

    private static final int MAX_CONNECTIONS = 8;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int IDLE_TIMEOUT_MILLIS = (int)TimeUnit.SECONDS.toMillis(60);

    private final ServerSocket serverSocket;
    private final AdminService service;
    private final JsonRpcServer rpcServer = new JsonRpcServer();
    private final Semaphore connections = new Semaphore(MAX_CONNECTIONS);

    /**
     * Binds the admin port on the loopback address.
     */
    public AdminServer(int port, Supplier<AdminInfo> info) throws IOException {
        this.serverSocket = new ServerSocket(port, MAX_CONNECTIONS, InetAddress.getLoopbackAddress());
        this.service = new AdminService(info);
    }

    public int getLocalPort() {
        return serverSocket.getLocalPort();
    }

    public InetAddress getInetAddress() {
        return serverSocket.getInetAddress();
    }

    public void start() {
        log.info("Admin endpoint listening on " + serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort());
        Thread.ofVirtual().name("AdminAccept").start(this::acceptLoop);
    }

    private void acceptLoop() {
        while(!serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch(IOException e) {
                return;
            }
            if(!connections.tryAcquire()) {
                closeQuietly(socket);
                continue;
            }
            Thread.ofVirtual().name("AdminSession").start(() -> {
                try {
                    serve(socket);
                } finally {
                    connections.release();
                }
            });
        }
    }

    private void serve(Socket socket) {
        try(socket) {
            socket.setSoTimeout(IDLE_TIMEOUT_MILLIS);
            BoundedLineReader in = new BoundedLineReader(socket.getInputStream(), MAX_REQUEST_BYTES);
            PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)));
            String request;
            while((request = in.readLine()) != null) {
                String response = rpcServer.handle(request, service);
                //a notification (a request without an id) has no response
                if(!response.isEmpty()) {
                    out.println(response);
                    out.flush();
                }
            }
        } catch(IOException e) {
            log.debug("Admin connection ended: {}", e.getMessage());
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch(IOException e) {
            //ignore
        }
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch(IOException e) {
            log.debug("Error closing admin port", e);
        }
    }

    @JsonRpcService
    public static class AdminService {
        private final Supplier<AdminInfo> info;

        AdminService(Supplier<AdminInfo> info) {
            this.info = info;
        }

        @JsonRpcMethod("getinfo")
        public AdminInfo getInfo() {
            return info.get();
        }
    }
}
