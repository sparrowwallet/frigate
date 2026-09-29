package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * A fake Electrum backend serving many connections at once, as Frigate opens one per client session. Each connection tracks its
 * own scripthash subscriptions, and a status change is notified only on the connections subscribed to that scripthash, as a
 * real Electrum server does. Statuses are served from a shared map, where an absent scripthash has a null status.
 */
public class FakeElectrumBackend implements Closeable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ServerSocket serverSocket;
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final Map<String, String> statuses = new ConcurrentHashMap<>();
    private volatile boolean accepting = true;

    public FakeElectrumBackend() throws IOException {
        serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().name("FakeElectrumBackend").start(this::acceptLoop);
    }

    public String getUrl() {
        return "tcp://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + serverSocket.getLocalPort();
    }

    /** While not accepting, new connections are closed as soon as they are accepted, simulating a backend that is down. */
    public void setAccepting(boolean accepting) {
        this.accepting = accepting;
    }

    /** Sets a scripthash's status, notifying it on every open connection subscribed to it if notify is true. */
    public void setStatus(String scriptHash, String status, boolean notify) {
        if(status == null) {
            statuses.remove(scriptHash);
        } else {
            statuses.put(scriptHash, status);
        }
        if(notify) {
            for(Connection connection : getOpenConnections()) {
                if(connection.subscriptions.contains(scriptHash)) {
                    connection.send(notification(scriptHash, status));
                }
            }
        }
    }

    /** Sends a notification on every open connection regardless of its subscriptions, as a misbehaving backend might. */
    public void notifyAllConnections(String scriptHash, String status) {
        for(Connection connection : getOpenConnections()) {
            connection.send(notification(scriptHash, status));
        }
    }

    public void dropAllConnections() {
        for(Connection connection : connections) {
            connection.close();
        }
    }

    public List<Connection> getOpenConnections() {
        return connections.stream().filter(connection -> connection.open).toList();
    }

    /** @return the open connections subscribed to the scripthash */
    public List<Connection> getSubscribedConnections(String scriptHash) {
        return getOpenConnections().stream().filter(connection -> connection.subscriptions.contains(scriptHash)).toList();
    }

    public static void await(BooleanSupplier condition, String description) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while(!condition.getAsBoolean()) {
            if(System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + description);
            }
            Thread.sleep(10);
        }
    }

    private void acceptLoop() {
        try {
            while(true) {
                Socket socket = serverSocket.accept();
                if(!accepting) {
                    socket.close();
                    continue;
                }
                Connection connection = new Connection(socket);
                connections.add(connection);
                Thread.ofVirtual().name("FakeElectrumBackendConnection").start(connection::serve);
            }
        } catch(IOException e) {
            //closed
        }
    }

    private String respond(Connection connection, JsonNode request) {
        String method = request.path("method").asText();
        JsonNode params = request.path("params");
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        switch(method) {
            case "server.version" -> {
                ArrayNode result = response.putArray("result");
                result.add("FakeElectrumBackend 1.0");
                result.add("1.4");
            }
            case "blockchain.scripthash.subscribe" -> {
                String scriptHash = params.path(0).asText();
                connection.subscriptions.add(scriptHash);
                response.put("result", statuses.get(scriptHash));
            }
            case "blockchain.scripthash.unsubscribe" -> response.put("result", connection.subscriptions.remove(params.path(0).asText()));
            case "blockchain.scripthash.get_history" -> response.putArray("result");
            default -> response.putNull("result");
        }
        return response.toString();
    }

    private static String notification(String scriptHash, String status) {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "blockchain.scripthash.subscribe");
        ArrayNode params = notification.putArray("params");
        params.add(scriptHash);
        params.add(status);
        return notification.toString();
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        dropAllConnections();
    }

    public class Connection {
        private final Socket socket;
        private final PrintWriter out;
        private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
        private volatile boolean open = true;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        public Set<String> getSubscriptions() {
            return subscriptions;
        }

        public boolean isOpen() {
            return open;
        }

        synchronized void send(String line) {
            out.print(line + "\n");
            out.flush();
        }

        private void serve() {
            try(BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while((line = in.readLine()) != null) {
                    send(respond(this, MAPPER.readTree(line)));
                }
            } catch(IOException e) {
                //closed
            } finally {
                close();
            }
        }

        void close() {
            open = false;
            try {
                socket.close();
            } catch(IOException e) {
                //ignore
            }
        }
    }
}
