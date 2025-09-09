package com.sparrowwallet.frigate.electrum;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sparrowwallet.frigate.bitcoind.BitcoindClient;
import com.sparrowwallet.frigate.index.IndexQuerier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

public class ElectrumServerRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ElectrumServerRunnable.class);
    public static final int DEFAULT_PORT = 57001;

    private final BitcoindClient bitcoindClient;
    private final IndexQuerier indexQuerier;

    protected ServerSocket serverSocket = null;
    protected boolean stopped = false;
    protected Thread runningThread = null;
    protected ExecutorService requestPool = Executors.newFixedThreadPool(10, r -> {
        ThreadFactory namedThreadFactory = new ThreadFactoryBuilder().setNameFormat("ElectrumServerRequest-%d").build();
        Thread t = namedThreadFactory.newThread(r);
        t.setDaemon(true);
        return t;
    });

    public ElectrumServerRunnable(BitcoindClient bitcoindClient, IndexQuerier indexQuerier) {
        this.bitcoindClient = bitcoindClient;
        this.indexQuerier = indexQuerier;
        openServerSocket();
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public void run() {
        synchronized(this) {
            this.runningThread = Thread.currentThread();
        }

        log.info("Electrum server listening on port {}", getPort());

        while(!isStopped()) {
            Socket clientSocket;
            try {
                clientSocket = this.serverSocket.accept();
            } catch(IOException e) {
                if(isStopped()) {
                    break;
                }
                throw new RuntimeException("Error accepting client connection", e);
            }
            RequestHandler requestHandler = new RequestHandler(clientSocket, bitcoindClient, indexQuerier);
            this.requestPool.execute(requestHandler);
        }

        this.requestPool.shutdown();
    }

    private synchronized boolean isStopped() {
        return stopped;
    }

    public synchronized void stop() {
        stopped = true;
        try {
            serverSocket.close();
        } catch(IOException e) {
            throw new RuntimeException("Error closing server", e);
        }
    }

    private void openServerSocket() {
        try {
            serverSocket = new ServerSocket(DEFAULT_PORT);
        } catch(IOException e) {
            throw new RuntimeException("Cannot open electrum server port", e);
        }
    }
}
