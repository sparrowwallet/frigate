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
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A newline-delimited JSON-RPC connection to an Electrum server. Requests are serial: pass() writes one request and waits for
 * the response carrying the same ids. A reader thread running readInputLoop() dispatches notifications to the subscription
 * service and queues responses for pass(), never waiting for a caller to collect them, so notifications keep flowing even
 * when a response is not collected (for example after a request timed out).
 *
 * The transport can be connected again after a connection is lost. Each connection has its own reader, started on a new thread
 * after connect(), and responses and loss signals are tagged with their connection, so nothing from an abandoned connection
 * (such as its reader exiting late) can affect the current one.
 *
 * Every line read is given a sequence number that increases across all transports. It orders a notification against the
 * response to a request (see getReadSequence()), which lets callers tell which of two statuses for a scripthash is newer.
 */
public class ElectrumTransport implements Transport, Closeable {
    private static final Logger log = LoggerFactory.getLogger(ElectrumTransport.class);

    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private static final AtomicLong READ_SEQUENCE = new AtomicLong();
    private static final ThreadLocal<Long> DELIVERED_SEQUENCE = ThreadLocal.withInitial(() -> 0L);
    private static final ScheduledExecutorService WRITE_WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ElectrumTransportWriteWatchdog");
        thread.setDaemon(true);
        return thread;
    });

    private final HostAndPort electrumServer;
    private final Protocol protocol;
    private final Object subscriptionService;
    private final long requestTimeoutMillis;

    private final JsonRpcServer jsonRpcServer = new JsonRpcServer();
    private final ReentrantLock clientRequestLock = new ReentrantLock();
    private final BlockingQueue<Message> responses = new LinkedBlockingQueue<>();

    private volatile Connection connection;
    private volatile boolean closed;
    private volatile long lastActivityNanos = System.nanoTime();
    private volatile Exception lastException;

    public ElectrumTransport(HostAndPort electrumServer, Protocol protocol, Object subscriptionService) {
        this(electrumServer, protocol, subscriptionService, 0);
    }

    /**
     * @param requestTimeoutMillis the maximum time to connect (including the TLS handshake), and to complete each request
     *                             (writing it and receiving the response), or 0 to wait indefinitely. A request that times out
     *                             closes the connection, as the state of a backend that has not responded is unknown, and a
     *                             fresh connection cannot deliver its late response.
     */
    public ElectrumTransport(HostAndPort electrumServer, Protocol protocol, Object subscriptionService, long requestTimeoutMillis) {
        this.electrumServer = electrumServer;
        this.protocol = protocol;
        this.subscriptionService = subscriptionService;
        this.requestTimeoutMillis = requestTimeoutMillis;
    }

    /**
     * Returns the read sequence of the backend message most recently delivered to the current thread: on the reader thread, the
     * notification being dispatched; on a calling thread, the response last returned by pass().
     */
    public static long getReadSequence() {
        return DELIVERED_SEQUENCE.get();
    }

    public void connect() throws IOException {
        if(closed) {
            throw new BackendUnavailableException("transport closed");
        }

        String host = electrumServer.getHost();
        int port = electrumServer.hasPort() ? electrumServer.getPort() : protocol.getDefaultPort();

        SocketFactory socketFactory;
        if(protocol == Protocol.SSL) {
            SSLSocketFactory sslSocketFactory = SslUtil.getTrustAllSocketFactory();
            if(sslSocketFactory == null) {
                throw new IOException("Could not create SSL socket factory for Electrum server " + host);
            }
            socketFactory = sslSocketFactory;
        } else {
            socketFactory = SocketFactory.getDefault();
        }

        //socket timeouts are ints, so clamp rather than overflow to a negative value for very long timeouts
        int socketTimeoutMillis = (int)Math.min(requestTimeoutMillis, Integer.MAX_VALUE);
        Socket newSocket = socketFactory.createSocket();
        try {
            newSocket.connect(new InetSocketAddress(host, port), socketTimeoutMillis);
            if(newSocket instanceof SSLSocket sslSocket) {
                //complete the handshake here under the timeout, rather than lazily on the first read or write, where it has none
                sslSocket.setSoTimeout(socketTimeoutMillis);
                sslSocket.startHandshake();
                sslSocket.setSoTimeout(0);
            }
        } catch(UnknownHostException e) {
            newSocket.close();
            throw new IOException("Unknown host " + host, e);
        } catch(IOException e) {
            newSocket.close();
            throw new IOException("Error connecting to Electrum server " + electrumServer + ": " + e.getMessage(), e);
        }

        Connection newConnection = new Connection(newSocket);
        Connection previous = connection;
        if(previous != null) {
            previous.close();
        }
        //the response queue is not cleared: a pass() still waiting on the previous connection needs its connection-lost signal,
        //and messages left over from previous connections are skipped by pass() as they are tagged with their connection
        lastException = null;
        lastActivityNanos = System.nanoTime();
        this.connection = newConnection;

        if(closed) {
            //closed while connecting
            newConnection.close();
            throw new BackendUnavailableException("transport closed");
        }
    }

    public boolean isConnected() {
        Connection current = connection;
        return current != null && current.open;
    }

    /**
     * @return nanoseconds since a request was written or a message was read on the current connection, or since it was established
     */
    public long getIdleNanos() {
        return System.nanoTime() - lastActivityNanos;
    }

    @Override
    public String pass(String request) throws IOException {
        Set<String> sentIdSet = extractIdSet(request);
        clientRequestLock.lock();
        try {
            Connection current = connection;
            if(current == null || !current.open) {
                throw new BackendUnavailableException(closed ? "transport closed" : "not connected");
            }

            long deadline = requestTimeoutMillis > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(requestTimeoutMillis) : 0;
            writeRequest(current, request);

            while(true) {
                Message message = deadline == 0 ? responses.take() : responses.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
                if(message == null) {
                    log.warn("Request to Electrum server " + electrumServer + " timed out after " + requestTimeoutMillis + "ms, closing connection");
                    current.close();
                    throw new BackendUnavailableException("request timed out");
                }
                if(message.connection() != current) {
                    //left over from an abandoned connection
                    continue;
                }
                if(message.isConnectionLost()) {
                    throw new BackendUnavailableException("connection lost");
                }

                Set<String> recvIdSet = extractIdSet(message.json());
                if(sentIdSet.equals(recvIdSet)) {
                    DELIVERED_SEQUENCE.set(message.sequence());
                    return message.json();
                }
                log.info("Discarding stale response with ids " + recvIdSet + " (expected " + sentIdSet + ")");
            }
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted waiting for response from Electrum server " + electrumServer);
        } finally {
            clientRequestLock.unlock();
        }
    }

    /**
     * Writes a request. Socket timeouts do not apply to writes, so a write blocked by a backend that has stopped reading is
     * bounded by a watchdog that closes the connection at the request timeout, failing the write.
     */
    private void writeRequest(Connection current, String request) throws IOException {
        log.debug("> " + request);

        ScheduledFuture<?> watchdog = requestTimeoutMillis > 0 ? WRITE_WATCHDOG.schedule(current::close, requestTimeoutMillis, TimeUnit.MILLISECONDS) : null;
        current.out.println(request);
        boolean failed = current.out.checkError();
        lastActivityNanos = System.nanoTime();
        boolean timedOut = watchdog != null && !watchdog.cancel(false);
        if(failed || timedOut) {
            if(timedOut) {
                log.warn("Request to Electrum server " + electrumServer + " could not be written within " + requestTimeoutMillis + "ms, closing connection");
            }
            current.close();
            throw new BackendUnavailableException(timedOut ? "request timed out" : "connection lost");
        }
    }

    /**
     * Reads from the current connection until it is lost or closed, dispatching notifications and queuing responses. Call once
     * per connect(), on a new thread. Returns normally; if the connection was lost rather than closed, getLastException() holds
     * the cause.
     */
    public void readInputLoop() {
        Connection current = connection;
        if(current == null) {
            return;
        }

        try {
            while(!closed) {
                String received = current.in.readLine();
                if(received == null) {
                    throw new EOFException("Connection closed by Electrum server " + electrumServer);
                }
                lastActivityNanos = System.nanoTime();
                log.debug("< " + received);

                //the sequence is taken before checking the connection is still open: if it is, connect() has not yet replaced it,
                //so every line of a replacing connection is read later and has a higher sequence. Lines still buffered from a
                //replaced connection are dropped, so a stale status cannot overwrite a newer one from the current connection.
                long sequence = READ_SEQUENCE.incrementAndGet();
                if(!current.open) {
                    break;
                }
                if(isNotification(received)) {
                    DELIVERED_SEQUENCE.set(sequence);
                    try {
                        jsonRpcServer.handle(received, subscriptionService);
                    } catch(Exception e) {
                        log.error("Error handling notification from Electrum server", e);
                    }
                } else {
                    responses.add(new Message(received, sequence, current));
                }
            }
        } catch(Exception e) {
            if(!closed && current == connection) {
                log.trace("Connection error while reading", e);
                lastException = e;
            }
        } finally {
            current.open = false;
            responses.add(Message.connectionLost(current));
        }
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

    /**
     * Closes the transport permanently.
     */
    @Override
    public void close() throws IOException {
        closed = true;
        Connection current = connection;
        if(current != null) {
            current.close();
            responses.add(Message.connectionLost(current));
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

    private static final class Connection {
        private final Socket socket;
        private final PrintWriter out;
        private final BufferedReader in;
        private volatile boolean open = true;

        Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)));
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        }

        /**
         * Closes this connection only; its reader's loop ends, and the transport may be connected again.
         *
         * The close is abortive (SO_LINGER 0): it may be called while another thread is blocked writing, and an SSL socket's
         * graceful close first sends close_notify, which waits without limit for the write lock the blocked writer holds.
         * Discarding unsent data does not matter on a connection that is being abandoned.
         */
        void close() {
            open = false;
            try {
                socket.setSoLinger(true, 0);
            } catch(SocketException e) {
                //already closed
            }
            try {
                socket.close();
            } catch(IOException e) {
                log.debug("Error closing connection to Electrum server", e);
            }
        }
    }

    /**
     * A response read from a connection, or (with null json) the signal that the connection has been lost.
     */
    private record Message(String json, long sequence, Connection connection) {
        static Message connectionLost(Connection connection) {
            return new Message(null, 0, connection);
        }

        boolean isConnectionLost() {
            return json == null;
        }
    }
}
