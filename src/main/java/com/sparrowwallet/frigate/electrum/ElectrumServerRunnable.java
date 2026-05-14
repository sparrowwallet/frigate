package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.frigate.ConfigurationException;
import com.sparrowwallet.frigate.bitcoind.BitcoindClient;
import com.sparrowwallet.frigate.index.IndexQuerier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ElectrumServerRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ElectrumServerRunnable.class);
    private static final int LISTEN_BACKLOG = 50;

    private static final Set<String> ALLOWED_TLS_PROTOCOLS = Set.of("TLSv1.2", "TLSv1.3");

    private final BitcoindClient bitcoindClient;
    private final IndexQuerier indexQuerier;
    private final InetSocketAddress tcpBind;
    private final InetSocketAddress sslBind;
    private final List<ServerSocket> serverSockets = new ArrayList<>();

    protected volatile boolean stopped = false;
    protected ExecutorService requestPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ElectrumServerRequest-", 0).factory());

    public ElectrumServerRunnable(BitcoindClient bitcoindClient, IndexQuerier indexQuerier, InetSocketAddress tcpBind, InetSocketAddress sslBind, SSLContext sslContext) {
        this.bitcoindClient = bitcoindClient;
        this.indexQuerier = indexQuerier;
        this.tcpBind = tcpBind;
        this.sslBind = sslBind;

        if(tcpBind == null && sslBind == null) {
            throw new ConfigurationException("At least one of tcp or ssl must be enabled under [server] in config.toml");
        }

        if(sslBind != null && sslContext == null) {
            throw new ConfigurationException("SSL: ssl listener configured but no SSLContext was supplied");
        }

        openServerSockets(sslContext);
    }

    public InetSocketAddress getTcpBind() {
        return tcpBind;
    }

    public InetSocketAddress getSslBind() {
        return sslBind;
    }

    public void run() {
        StringBuilder banner = new StringBuilder("Electrum server listening on");
        if(tcpBind != null) banner.append(" tcp://").append(formatBind(tcpBind));
        if(sslBind != null) banner.append(" ssl://").append(formatBind(sslBind));
        log.info(banner.toString());

        CountDownLatch done = new CountDownLatch(serverSockets.size());
        for(ServerSocket ss : serverSockets) {
            Thread.ofVirtual().name("ElectrumAccept-" + ss.getLocalPort()).start(() -> {
                try {
                    acceptLoop(ss);
                } finally {
                    done.countDown();
                }
            });
        }

        try {
            done.await();
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        this.requestPool.shutdown();
    }

    private void acceptLoop(ServerSocket serverSocket) {
        while(!stopped) {
            Socket clientSocket;
            try {
                clientSocket = serverSocket.accept();
            } catch(IOException e) {
                if(stopped) {
                    return;
                }
                log.error("Error accepting client connection on port " + serverSocket.getLocalPort(), e);
                return;
            }
            RequestHandler requestHandler = new RequestHandler(clientSocket, bitcoindClient, indexQuerier);
            this.requestPool.execute(requestHandler);
        }
    }

    public synchronized void stop() {
        stopped = true;
        for(ServerSocket ss : serverSockets) {
            try {
                ss.close();
            } catch(IOException e) {
                log.error("Error closing server socket on port " + ss.getLocalPort(), e);
            }
        }
    }

    private void openServerSockets(SSLContext sslContext) {
        try {
            if(tcpBind != null) {
                ServerSocket plain = new ServerSocket(tcpBind.getPort(), LISTEN_BACKLOG, tcpBind.getAddress());
                serverSockets.add(plain);
            }
            if(sslBind != null) {
                SSLServerSocket sslSocket = (SSLServerSocket)sslContext.getServerSocketFactory().createServerSocket(sslBind.getPort(), LISTEN_BACKLOG, sslBind.getAddress());
                sslSocket.setNeedClientAuth(false);
                sslSocket.setEnabledProtocols(restrictedProtocols(sslSocket.getSupportedProtocols()));
                serverSockets.add(sslSocket);
            }
        } catch(IOException e) {
            for(ServerSocket opened : serverSockets) {
                try {
                    opened.close();
                } catch(IOException ignored) {
                    //ignore
                }
            }
            serverSockets.clear();
            throw new RuntimeException("Cannot open electrum server port", e);
        }
    }

    private static String formatBind(InetSocketAddress addr) {
        return addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }

    private static String[] restrictedProtocols(String[] supported) {
        List<String> enabled = new ArrayList<>(2);
        Set<String> supportedSet = new HashSet<>(Arrays.asList(supported));
        for(String p : ALLOWED_TLS_PROTOCOLS) {
            if(supportedSet.contains(p)) {
                enabled.add(p);
            }
        }

        if(enabled.isEmpty()) {
            throw new ConfigurationException("SSL: JVM supports neither TLSv1.2 nor TLSv1.3 (supported: " + Arrays.toString(supported) + ")");
        }

        return enabled.toArray(new String[0]);
    }
}
