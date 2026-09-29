package com.sparrowwallet.frigate.electrum;

import com.sparrowwallet.frigate.ConfigurationException;
import com.sparrowwallet.frigate.bitcoind.BitcoindClient;
import com.sparrowwallet.frigate.index.IndexQuerier;
import com.sparrowwallet.frigate.io.BackendTls;
import com.google.common.util.concurrent.Uninterruptibles;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.frigate.Frigate;
import com.sparrowwallet.frigate.io.Config;
import com.sparrowwallet.frigate.io.Protocol;
import com.sparrowwallet.frigate.io.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ElectrumServerRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ElectrumServerRunnable.class);
    private static final int LISTEN_BACKLOG = 50;
    private static final Duration FORCE_CLOSE_WAIT = Duration.ofSeconds(2);
    private static final long SHUTDOWN_POLL_MILLIS = 20;

    private static final Set<String> ALLOWED_TLS_PROTOCOLS = Set.of("TLSv1.2", "TLSv1.3");

    private final BitcoindClient bitcoindClient;
    private final IndexQuerier indexQuerier;
    private final InetSocketAddress tcpBind;
    private final InetSocketAddress sslBind;
    private final List<ServerSocket> serverSockets = new ArrayList<>();
    private final ConnectionGate connectionGate;
    private final BackendTls backendTls;
    private ScheduledExecutorService statsExecutor;
    private final AdminServer adminServer;
    private final long startNanos = System.nanoTime();
    private Duration healthStatsInterval = Duration.ofMinutes(5);
    private Duration usageStatsInterval = Duration.ofHours(1);
    private final Set<RequestHandler> sessions = ConcurrentHashMap.newKeySet();

    protected volatile boolean stopped = false;
    protected ExecutorService requestPool = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("ElectrumServerRequest-", 0).factory());

    public ElectrumServerRunnable(BitcoindClient bitcoindClient, IndexQuerier indexQuerier, InetSocketAddress tcpBind, InetSocketAddress sslBind, SSLContext sslContext) {
        this.bitcoindClient = bitcoindClient;
        this.indexQuerier = indexQuerier;
        this.tcpBind = tcpBind;
        this.sslBind = sslBind;
        //built once, so a bad pinned certificate fails at startup and an unverified ssl:// backend is warned about once
        this.backendTls = BackendTls.fromConfig(Config.get().getServer());
        Config.LimitsConfig limits = Config.get().getLimits();
        this.connectionGate = new ConnectionGate(limits.getMaxConnections(), limits.getMaxConnectionsPerIp(), limits.getMaxSubscriptions(), limits.getMaxSubscriptionsPerIp());

        if(tcpBind == null && sslBind == null) {
            throw new ConfigurationException("At least one of tcp or ssl must be enabled under [server] in config.toml");
        }

        if(sslBind != null && sslContext == null) {
            throw new ConfigurationException("SSL: ssl listener configured but no SSLContext was supplied");
        }

        openServerSockets(sslContext);

        int adminPort = Config.get().getServer().getAdminPort();
        if(adminPort > 0) {
            try {
                this.adminServer = new AdminServer(adminPort, this::getAdminInfo);
            } catch(IOException e) {
                stop();
                throw new RuntimeException("Cannot open admin port " + adminPort, e);
            }
        } else {
            this.adminServer = null;
        }
    }

    public InetSocketAddress getTcpBind() {
        return tcpBind;
    }

    public InetSocketAddress getSslBind() {
        return sslBind;
    }

    /**
     * @return the port the plaintext listener is bound to, which differs from the configured bind port when that is 0, or -1
     */
    public int getTcpLocalPort() {
        return serverSockets.stream().filter(ss -> !(ss instanceof SSLServerSocket)).mapToInt(ServerSocket::getLocalPort).findFirst().orElse(-1);
    }

    public void run() {
        StringBuilder banner = new StringBuilder("Electrum server listening on");
        if(tcpBind != null) banner.append(" tcp://").append(formatBind(tcpBind));
        if(sslBind != null) banner.append(" ssl://").append(formatBind(sslBind));
        log.info(banner.toString());
        startStats();
        if(adminServer != null) {
            adminServer.start();
        }

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

            ConnectionGate.IpKey ipKey = connectionGate.tryAcquire(clientSocket.getInetAddress());
            if(ipKey == null) {
                //refused without a response, as writing one would itself be an amplification vector
                log.debug("Refusing connection from " + clientSocket.getRemoteSocketAddress() + ": connection limit reached");
                closeQuietly(clientSocket);
                continue;
            }

            try {
                this.requestPool.execute(() -> runSession(clientSocket, ipKey));
            } catch(RejectedExecutionException e) {
                connectionGate.release(ipKey);
                closeQuietly(clientSocket);
            }
        }
    }

    /**
     * Runs a client session, releasing its connection slot and removing it from the live sessions however it ends.
     */
    private void runSession(Socket clientSocket, ConnectionGate.IpKey ipKey) {
        RequestHandler requestHandler = null;
        try {
            requestHandler = new RequestHandler(clientSocket, bitcoindClient, indexQuerier, connectionGate, ipKey, backendTls);
            sessions.add(requestHandler);
            //a session accepted just before shutdown may join after shutdown has gone through the live sessions: it is not started,
            //and its socket, which nothing has written to yet, is closed here
            if(stopped) {
                closeQuietly(clientSocket);
                return;
            }
            requestHandler.run();
        } catch(RuntimeException e) {
            log.error("Error in session for client " + clientSocket.getRemoteSocketAddress(), e);
            closeQuietly(clientSocket);
        } finally {
            if(requestHandler != null) {
                sessions.remove(requestHandler);
            }
            connectionGate.release(ipKey);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch(IOException e) {
            //ignore
        }
    }

    /**
     * Shuts the server down gracefully: listeners close so no new sessions start, idle sessions close at once, and sessions handling
     * a request finish it before closing. Sessions still running after the drain deadline are closed abortively. Each session's
     * backend connection closes with it.
     */
    public void shutdown(Duration drain) {
        stop();
        for(RequestHandler session : sessions) {
            session.beginShutdown();
        }

        if(!awaitSessionsEnded(drain)) {
            log.info("Closing " + sessions.size() + " sessions still running after the " + drain.toSeconds() + "s shutdown drain");
            for(RequestHandler session : sessions) {
                session.forceClose();
            }
            awaitSessionsEnded(FORCE_CLOSE_WAIT);
        }
    }

    /**
     * Waits for the live sessions to end. Shutdown is already bounded, so an interrupt does not cut the wait short (which would skip
     * the wait after forced closes); the interrupt is restored for the caller.
     */
    private boolean awaitSessionsEnded(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while(!sessions.isEmpty()) {
            if(System.nanoTime() >= deadline) {
                return false;
            }
            Uninterruptibles.sleepUninterruptibly(SHUTDOWN_POLL_MILLIS, TimeUnit.MILLISECONDS);
        }
        return true;
    }

    /**
     * @return a snapshot of the server's state
     */
    public ServerStats getStats() {
        return ServerStats.collect(sessions, connectionGate, Config.get().getServer().getBackendElectrumServerObj() != null, bitcoindClient);
    }

    /**
     * @return the admin endpoint's getinfo response, see AdminInfo
     */
    public AdminInfo getAdminInfo() {
        ServerStats stats = getStats();
        Server backend = Config.get().getServer().getBackendElectrumServerObj();
        String backendTlsMode = backend != null && backend.getProtocol() == Protocol.SSL ? backendTls.getMode().name().toLowerCase(Locale.ROOT).replace('_', ' ') : null;
        return new AdminInfo(Frigate.SERVER_VERSION, Network.get().getName(), TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos),
                AdminInfo.Health.of(stats), AdminInfo.Usage.of(stats), Config.get().getLimits(), backendTlsMode);
    }

    /**
     * @return the port the admin endpoint is bound to, or -1 if it is disabled
     */
    public int getAdminLocalPort() {
        return adminServer != null ? adminServer.getLocalPort() : -1;
    }

    AdminServer getAdminServer() {
        return adminServer;
    }

    /**
     * Schedules the health line every five minutes and the hourly usage line, each unless disabled, see ServerStatsLog.
     */
    private synchronized void startStats() {
        boolean health = Config.get().getServer().isHealthStatsEnabled();
        boolean usage = Config.get().getScan().isMetricsEnabled();
        if((!health && !usage) || stopped) {
            return;
        }

        ServerStatsLog statsLog = new ServerStatsLog(this::getStats, ServerMetrics::takeNotifierQueueHighWater, System::nanoTime);
        statsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ElectrumServerStats");
            thread.setDaemon(true);
            return thread;
        });
        if(health) {
            schedule(() -> statsLog.nextHealthLine().ifPresent(log::info), healthStatsInterval);
        }
        if(usage) {
            schedule(() -> statsLog.nextUsageLine().ifPresent(log::info), usageStatsInterval);
        }
    }

    private void schedule(Runnable task, Duration interval) {
        statsExecutor.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch(Throwable t) {
                //a scheduled task that throws is never run again
                log.error("Error logging server stats", t);
            }
        }, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Sets the stats line intervals, before the server runs; for tests.
     */
    void setStatsIntervals(Duration healthStatsInterval, Duration usageStatsInterval) {
        this.healthStatsInterval = healthStatsInterval;
        this.usageStatsInterval = usageStatsInterval;
    }

    /**
     * @return the sessions currently connected
     */
    public Set<RequestHandler> getSessions() {
        return Collections.unmodifiableSet(sessions);
    }

    public ConnectionGate getConnectionGate() {
        return connectionGate;
    }

    public synchronized void stop() {
        stopped = true;
        if(statsExecutor != null) {
            statsExecutor.shutdownNow();
        }
        if(adminServer != null) {
            adminServer.close();
        }
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
