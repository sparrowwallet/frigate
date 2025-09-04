package com.sparrowwallet.frigate.cli;

import com.github.arteam.simplejsonrpc.client.Transport;
import com.github.arteam.simplejsonrpc.server.JsonRpcServer;
import com.google.common.net.HostAndPort;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static com.sparrowwallet.frigate.electrum.ElectrumServerRunnable.DEFAULT_PORT;

public class ElectrumTransport implements Transport, Closeable {
    private static final Logger log = LoggerFactory.getLogger(ElectrumTransport.class);

    private final HostAndPort electrumServer;
    private Socket socket;
    private String response;

    private boolean firstRead = true;

    private final CountDownLatch readReadySignal = new CountDownLatch(1);

    private final ReentrantLock readLock = new ReentrantLock();
    private final Condition readingCondition = readLock.newCondition();

    private final ReentrantLock clientRequestLock = new ReentrantLock();
    private boolean running = false;
    private volatile boolean reading = true;
    private boolean closed = false;
    private Exception lastException;

    private final Gson gson = new Gson();

    private final JsonRpcServer jsonRpcServer = new JsonRpcServer();
    private final SubscriptionService subscriptionService = new SubscriptionService();

    public ElectrumTransport(HostAndPort electrumServer) {
        this.electrumServer = electrumServer;
        try {
            String host = electrumServer.getHost();
            int port = electrumServer.hasPort() ? electrumServer.getPort() : DEFAULT_PORT;

            SocketFactory socketFactory = SocketFactory.getDefault();
            this.socket = socketFactory.createSocket(host, port);
            this.running = true;
        } catch(UnknownHostException e) {
            log.error("Unknown host: " + electrumServer.getHost());
        } catch(IOException e) {
            log.error("Error connecting to Electrum server: " + electrumServer.getHost());
        }
    }

    @Override
    public String pass(String request) throws IOException {
        clientRequestLock.lock();
        try {
            Rpc sentRpc = request.startsWith("{") ? gson.fromJson(request, Rpc.class) : null;
            Rpc recvRpc;
            String recv;

            writeRequest(request);
            do {
                recv = readResponse();
                recvRpc = recv.startsWith("{") ? gson.fromJson(response, Rpc.class) : null;
            } while(!Objects.equals(recvRpc, sentRpc));

            return recv;
        } finally {
            clientRequestLock.unlock();
        }
    }

    protected void writeRequest(String request) throws IOException {
        log.debug("> " + request);

        if(socket == null) {
            throw new IllegalStateException("Socket connection has not been established.");
        }

        PrintWriter out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)));
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

        try {
            if(!readLock.tryLock(1, TimeUnit.SECONDS)) {
                throw new IOException("No response from server");
            }
        } catch(InterruptedException e) {
            throw new IOException("Read thread interrupted");
        }

        try {
            if(firstRead) {
                readingCondition.signal();
                firstRead = false;
            }

            while(reading) {
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

            reading = true;

            readingCondition.signal();
            return response;
        } finally {
            readLock.unlock();
        }
    }

    public void readInputLoop() throws Exception {
        readLock.lock();
        readReadySignal.countDown();

        try {
            try {
                //Don't start reading until first RPC request is sent
                readingCondition.await();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

            while(running) {
                try {
                    String received = readInputStream(in);
                    if(received.contains("method") && !received.contains("error")) {
                        //Handle subscription notification
                        jsonRpcServer.handle(received, subscriptionService);
                    } else {
                        //Handle client's response
                        response = received;
                        reading = false;
                        readingCondition.signal();
                        readingCondition.await();
                    }
                } catch(InterruptedException e) {
                    //Restore interrupt status and continue
                    Thread.currentThread().interrupt();
                } catch(Exception e) {
                    log.trace("Connection error while reading", e);
                    if(running) {
                        lastException = e;
                        reading = false;
                        readingCondition.signal();
                        //Allow this thread to terminate as we will need to reconnect with a new transport anyway
                        running = false;
                    }
                }
            }
        } catch(IOException e) {
            if(!closed) {
                log.error("Error opening socket inputstream", e);
            }
            if(running) {
                lastException = e;
                reading = false;
                readingCondition.signal();
                //Allow this thread to terminate as we will need to reconnect with a new transport anyway
                running = false;
            }
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

    public Exception getLastException() {
        return lastException;
    }

    @Override
    public void close() throws IOException {
        if(socket != null) {
            socket.close();
        }
        closed = true;
    }

    public static class Rpc {
        public String id;

        @Override
        public boolean equals(Object o) {
            if(this == o) {
                return true;
            }
            if(o == null || getClass() != o.getClass()) {
                return false;
            }
            Rpc rpc = (Rpc) o;
            return Objects.equals(id, rpc.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }
}
