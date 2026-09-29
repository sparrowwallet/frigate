package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A line-based JSON-RPC client for tests, standing in for a wallet. A reader thread sorts incoming messages into responses and
 * notifications. Reading can be paused, so the client stops draining its socket like a wallet that has stalled.
 */
public class TestElectrumClient implements Closeable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Socket socket;
    private final PrintWriter out;
    private final BlockingQueue<JsonNode> responses = new LinkedBlockingQueue<>();
    private final BlockingQueue<JsonNode> notifications = new LinkedBlockingQueue<>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final CountDownLatch disconnected = new CountDownLatch(1);
    private volatile CountDownLatch resumeReading;

    public TestElectrumClient(int port) throws IOException {
        this(new Socket(), port, 0);
    }

    /** @param receiveBufferSize the socket receive buffer size, set small so a paused client stalls the sender sooner; 0 for the default */
    public TestElectrumClient(Socket unconnected, int port, int receiveBufferSize) throws IOException {
        this.socket = unconnected;
        if(receiveBufferSize > 0) {
            socket.setReceiveBufferSize(receiveBufferSize);
        }
        socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000);
        this.out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().name("TestElectrumClientReader").start(this::readLoop);
    }

    /** Sends a request and waits for its response. */
    public JsonNode request(String method, Object... params) throws IOException, InterruptedException {
        int id = send(method, params);
        return awaitResponse(id);
    }

    /** Sends a request without waiting for its response. */
    public int send(String method, Object... params) {
        int id = nextId.getAndIncrement();
        write(requestNode(id, method, params).toString());
        return id;
    }

    /** Sends one batch of requests with the same method, one per parameter, without waiting for the response. */
    public void sendBatch(String method, List<String> params) {
        ArrayNode batch = MAPPER.createArrayNode();
        for(String param : params) {
            batch.add(requestNode(nextId.getAndIncrement(), method, param));
        }
        write(batch.toString());
    }

    /** Sends one batch of requests with the same method, one per parameter, and waits for the batch response array. */
    public JsonNode requestBatch(String method, List<String> params) throws IOException, InterruptedException {
        sendBatch(method, params);

        JsonNode response = responses.poll(30, TimeUnit.SECONDS);
        if(response == null || !response.isArray()) {
            throw new IOException("No batch response: " + response);
        }
        return response;
    }

    private static ObjectNode requestNode(int id, String method, Object... params) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", MAPPER.valueToTree(params));
        return request;
    }

    /** Sends a raw line, which need not be valid JSON-RPC. */
    public void sendRaw(String line) {
        write(line);
    }

    /** Sends raw text without a line terminator, to deliver a request in pieces. */
    public synchronized void sendPartial(String text) {
        out.print(text);
        out.flush();
    }

    /** @return the next response, such as an error response without an id, or null if none arrives within the timeout */
    public JsonNode pollResponse(long timeout, TimeUnit unit) throws InterruptedException {
        return responses.poll(timeout, unit);
    }

    private synchronized void write(String line) {
        out.print(line + "\n");
        out.flush();
    }

    public JsonNode awaitResponse(int id) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while(true) {
            JsonNode response = responses.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if(response == null) {
                throw new IOException("No response for request " + id);
            }
            if(response.isArray()) {
                for(JsonNode item : response) {
                    if(item.path("id").asInt() == id) {
                        return item;
                    }
                }
            } else if(response.path("id").asInt() == id) {
                return response;
            }
        }
    }

    /**
     * Returns a notification parameter by position or name. Frigate sends named params (the JSON-RPC client library's default),
     * while the Electrum protocol documents positional ones, so both are accepted. A null status is omitted from named params.
     */
    public static JsonNode param(JsonNode notification, int index, String name) {
        JsonNode params = notification.path("params");
        return params.isArray() ? params.path(index) : params.path(name);
    }

    /** @return the next notification, or null if none arrives within the timeout */
    public JsonNode pollNotification(long timeout, TimeUnit unit) throws InterruptedException {
        return notifications.poll(timeout, unit);
    }

    public BlockingQueue<JsonNode> getNotifications() {
        return notifications;
    }

    /** Stops reading from the socket, so unread data backs up to the server like a stalled wallet. */
    public void pauseReading() {
        resumeReading = new CountDownLatch(1);
    }

    public void resumeReading() {
        CountDownLatch latch = resumeReading;
        resumeReading = null;
        if(latch != null) {
            latch.countDown();
        }
    }

    /** @return true if the server closed the connection within the timeout */
    public boolean awaitDisconnect(long timeout, TimeUnit unit) throws InterruptedException {
        return disconnected.await(timeout, unit);
    }

    private void readLoop() {
        try(BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while(true) {
                CountDownLatch paused = resumeReading;
                if(paused != null) {
                    paused.await();
                }
                if((line = in.readLine()) == null) {
                    break;
                }
                JsonNode message = MAPPER.readTree(line);
                if(!message.isArray() && message.has("method")) {
                    notifications.add(message);
                } else {
                    responses.add(message);
                }
            }
        } catch(IOException e) {
            //closed or reset
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            disconnected.countDown();
        }
    }

    @Override
    public void close() throws IOException {
        resumeReading();
        socket.close();
    }
}
