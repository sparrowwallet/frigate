package com.sparrowwallet.frigate.electrum;

import com.google.common.net.HostAndPort;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A scripted Electrum backend for tests, speaking newline-delimited JSON-RPC on a loopback port. Each line received is recorded
 * and passed to the responder, whose returned lines are written back in order.
 */
public class FakeElectrumServer implements Closeable {
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private final ServerSocket serverSocket;
    private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    private volatile Function<String, List<String>> responder = line -> List.of(response(idOf(line), "\"ok\""));
    private volatile Socket client;
    private volatile PrintWriter out;
    private final CountDownLatch clientConnected = new CountDownLatch(1);
    private int connectionCount;

    public FakeElectrumServer() throws IOException {
        this(0);
    }

    /** @param port the loopback port to listen on, or 0 for any free port */
    public FakeElectrumServer(int port) throws IOException {
        this(new ServerSocket(port, 50, InetAddress.getLoopbackAddress()));
    }

    /** Serves on a server socket bound by the caller, such as an SSL server socket. */
    public FakeElectrumServer(ServerSocket serverSocket) {
        this.serverSocket = serverSocket;
        Thread.ofVirtual().name("FakeElectrumServer").start(this::serve);
    }

    public HostAndPort getHostAndPort() {
        return HostAndPort.fromParts(InetAddress.getLoopbackAddress().getHostAddress(), serverSocket.getLocalPort());
    }

    public void setResponder(Function<String, List<String>> responder) {
        this.responder = responder;
    }

    public BlockingQueue<String> getReceived() {
        return received;
    }

    /** Writes a line to the connected client, unprompted. */
    public void send(String line) {
        sendPartial(line + "\n");
    }

    /** Writes raw text without a line terminator, to split a message across writes. */
    public void sendPartial(String text) {
        awaitClient();
        PrintWriter writer = out;
        writer.print(text);
        writer.flush();
    }

    /** Waits until a client has connected, as connect() on the client side can return before the connection is accepted here. */
    private void awaitClient() {
        try {
            if(!clientConnected.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("No client connected");
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Waits until the given number of connections have been accepted, so later sends go to the latest one. */
    public synchronized void awaitConnectionCount(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while(connectionCount < count) {
            long remaining = deadline - System.currentTimeMillis();
            if(remaining <= 0) {
                throw new IllegalStateException("Only " + connectionCount + " of " + count + " connections accepted");
            }
            wait(remaining);
        }
    }

    private synchronized void connectionAccepted() {
        connectionCount++;
        notifyAll();
    }

    public void closeClient() throws IOException {
        awaitClient();
        client.close();
    }

    private void serve() {
        try {
            while(!serverSocket.isClosed()) {
                Socket socket = serverSocket.accept();
                this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                this.client = socket;
                clientConnected.countDown();
                connectionAccepted();
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while((line = in.readLine()) != null) {
                        received.add(line);
                        for(String reply : responder.apply(line)) {
                            send(reply);
                        }
                    }
                } catch(IOException e) {
                    //connection reset by the client, accept the next
                }
            }
        } catch(IOException e) {
            //closed
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
        Socket socket = client;
        if(socket != null) {
            socket.close();
        }
    }

    public static String idOf(String request) {
        Matcher matcher = ID_PATTERN.matcher(request);
        return matcher.find() ? matcher.group(1) : "0";
    }

    public static String response(String id, String resultJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}";
    }

    public static String request(int id, String method) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":[]}";
    }

    public static String scriptHashNotification(String scriptHash, String statusJson) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"blockchain.scripthash.subscribe\",\"params\":[\"" + scriptHash + "\"," + statusJson + "]}";
    }
}
