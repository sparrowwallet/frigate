package com.sparrowwallet.frigate.electrum;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.github.arteam.simplejsonrpc.client.Transport;
import com.github.arteam.simplejsonrpc.server.JsonRpcServer;
import com.google.common.net.HostAndPort;
import com.sparrowwallet.frigate.io.Protocol;
import com.sparrowwallet.frigate.io.SslUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ElectrumTransport implements Transport, Closeable {
    private static final Logger log = LoggerFactory.getLogger(ElectrumTransport.class);

    private final HostAndPort electrumServer;
    private final Protocol protocol;
    private Socket socket;
    private String response;

    private boolean firstRead = true;

    private final CountDownLatch readReadySignal = new CountDownLatch(1);

    private final ReentrantLock readLock = new ReentrantLock();
    private final Condition readingCondition = readLock.newCondition();

    private final ReentrantLock clientRequestLock = new ReentrantLock();
    private volatile boolean running = false;
    private volatile boolean reading = true;
    private volatile boolean closed = false;
    private Exception lastException;

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private final JsonRpcServer jsonRpcServer = new JsonRpcServer();
    private final Object subscriptionService;

    private PrintWriter out;
    private BufferedReader in;

    public ElectrumTransport(HostAndPort electrumServer, Protocol protocol, Object subscriptionService) {
        this.electrumServer = electrumServer;
        this.protocol = protocol;
        this.subscriptionService = subscriptionService;
    }

    public void connect() {
        try {
            String host = electrumServer.getHost();
            int port = electrumServer.hasPort() ? electrumServer.getPort() : protocol.getDefaultPort();

            SocketFactory socketFactory;
            if(protocol == Protocol.SSL) {
                SSLSocketFactory sslSocketFactory = SslUtil.getTrustAllSocketFactory();
                if(sslSocketFactory == null) {
                    log.error("Could not create SSL socket factory for Electrum server: " + host);
                    return;
                }
                socketFactory = sslSocketFactory;
            } else {
                socketFactory = SocketFactory.getDefault();
            }

            this.socket = socketFactory.createSocket();
            this.socket.connect(new InetSocketAddress(host, port));
            this.socket.setSoTimeout(30000); // 30 second timeout for reads
            this.out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)));
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.running = true;
        } catch(UnknownHostException e) {
            log.error("Unknown host: " + electrumServer.getHost());
        } catch(IOException e) {
            log.error("Error connecting to Electrum server: " + electrumServer.getHost());
        }
    }

    @Override
    public String pass(String request) throws IOException {
        Set<String> sentIdSet = extractIdSet(request);
        clientRequestLock.lock();
        try {
            writeRequest(request);

            String recv;
            Set<String> recvIdSet;
            do {
                recv = readResponse();
                recvIdSet = extractIdSet(recv);
                if(!sentIdSet.equals(recvIdSet)) {
                    log.info("Discarding stale response with ids " + recvIdSet + " (expected " + sentIdSet + ")");
                }
            } while(!sentIdSet.equals(recvIdSet));

            return recv;
        } finally {
            clientRequestLock.unlock();
        }
    }

    protected void writeRequest(String request) throws IOException {
        log.debug("> " + request);

        if(out == null) {
            throw new IllegalStateException("Socket connection has not been established.");
        }

        out.println(request);
        out.flush();
    }

    private String readResponse() throws IOException {
        if(firstRead) {
            try {
                //Ensure read thread has started
                if(!readReadySignal.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("Read thread did not start");
                }
            } catch(InterruptedException e) {
                throw new IOException("Read ready await interrupted");
            }
        }

        readLock.lock();
        try {
            if(firstRead) {
                readingCondition.signal();
                firstRead = false;
            }

            while(reading && running) {
                try {
                    readingCondition.await();
                } catch(InterruptedException e) {
                    //Restore interrupt status and break
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if(lastException != null) {
                throw new IOException("Error reading response: " + lastException.getMessage(), lastException);
            }

            if(!running) {
                throw new IOException("Transport closed");
            }

            reading = true;

            readingCondition.signal();
            return response;
        } finally {
            readLock.unlock();
        }
    }

    public void readInputLoop() throws Exception {
        //Wait for first RPC request before starting to read. The lock must be acquired before
        //signaling readiness so readResponse() blocks until we reach the atomic await/unlock.
        readLock.lock();
        try {
            readReadySignal.countDown();
            if(running) {
                readingCondition.await();
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } finally {
            readLock.unlock();
        }

        while(running) {
            try {
                String received = readInputStream(in);
                if(isNotification(received)) {
                    jsonRpcServer.handle(received, subscriptionService);
                } else {
                    deliverResponse(received);
                }
            } catch(InterruptedException e) {
                //Restore interrupt status and continue
                Thread.currentThread().interrupt();
            } catch(Exception e) {
                if(!closed) {
                    log.trace("Connection error while reading", e);
                }
                if(running) {
                    signalException(e);
                    //Allow this thread to terminate as we will need to reconnect with a new transport anyway
                    running = false;
                }
            }
        }
    }

    private void deliverResponse(String received) throws InterruptedException {
        readLock.lock();
        try {
            response = received;
            reading = false;
            readingCondition.signal();
            while(!reading && running) {
                readingCondition.await();
            }
        } finally {
            readLock.unlock();
        }
    }

    private void signalException(Exception e) {
        readLock.lock();
        try {
            lastException = e;
            reading = false;
            readingCondition.signal();
        } finally {
            readLock.unlock();
        }
    }

    protected String readInputStream(BufferedReader in) throws IOException {
        String response = readLine(in);

        if(response == null) {
            throw new IOException("Could not connect to server " + electrumServer);
        }

        log.debug("< " + response);

        return response;
    }

    private String readLine(BufferedReader in) throws IOException {
        while(!socket.isClosed()) {
            try {
                return in.readLine();
            } catch(SocketTimeoutException e) {
                //ignore and continue
            }
        }

        return null;
    }

    private static boolean isNotification(String json) {
        try(JsonParser parser = JSON_FACTORY.createParser(json)) {
            if(parser.nextToken() != JsonToken.START_OBJECT) {
                return false;
            }
            while(parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if("method".equals(field)) {
                    return value == JsonToken.VALUE_STRING;
                }
                parser.skipChildren();
            }
            return false;
        } catch(Exception e) {
            log.warn("Could not parse JSON-RPC message from backend: " + e.getMessage());
            return false;
        }
    }

    public Exception getLastException() {
        return lastException;
    }

    @Override
    public void close() throws IOException {
        running = false;
        closed = true;

        readLock.lock();
        try {
            readingCondition.signalAll();
        } finally {
            readLock.unlock();
        }

        if(out != null) {
            out.close();
        }
        if(in != null) {
            in.close();
        }
        if(socket != null) {
            socket.close();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private static Set<String> extractIdSet(String json) {
        if(json == null || json.isEmpty()) {
            return Collections.emptySet();
        }
        Matcher m = ID_PATTERN.matcher(json);
        Set<String> ids = new LinkedHashSet<>();
        while(m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }
}
