package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.frigate.bitcoind.BitcoindClient;
import com.sparrowwallet.frigate.index.Index;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ElectrumServerRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ElectrumServerRunnable.class);
    public static final int DEFAULT_PORT = 57001;

    private final BitcoindClient bitcoindClient;
    private final Index index;

    protected ServerSocket serverSocket = null;
    protected boolean stopped = false;
    protected Thread runningThread = null;
    protected ExecutorService threadPool = Executors.newFixedThreadPool(10, r -> {
        Thread t = Executors.defaultThreadFactory().newThread(r);
        t.setDaemon(true);
        return t;
    });

    public ElectrumServerRunnable(BitcoindClient bitcoindClient, Index index) {
        this.bitcoindClient = bitcoindClient;
        this.index = index;
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
            RequestHandler requestHandler = new RequestHandler(clientSocket, bitcoindClient, index);
            this.threadPool.execute(requestHandler);
        }

        this.threadPool.shutdown();
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
