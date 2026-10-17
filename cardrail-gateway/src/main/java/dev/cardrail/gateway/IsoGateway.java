package dev.cardrail.gateway;

import dev.cardrail.iso8583.IsoCodec;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static java.lang.System.Logger.Level.DEBUG;
import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

/**
 * ISO 8583 TCP server: length-prefixed frames in, decoded requests to an {@link IsoRequestHandler},
 * responses out on the same connection.
 *
 * <p>Each connection is read by its own virtual thread and each request runs on another, so
 * requests on one connection are pipelined and responses are written as they complete, not in
 * arrival order; the acquirer matches them by STAN. {@code 0800} echo tests are answered here
 * without calling the handler.
 *
 * <pre>{@code
 * IsoGateway gateway = new IsoGateway(GatewayConfig.defaults().withPort(0), handler, listener);
 * gateway.start();
 * int port = gateway.port();
 * ...
 * gateway.shutdown(Duration.ofSeconds(10));
 * }</pre>
 */
public final class IsoGateway implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(IsoGateway.class.getName());

    private enum State { NEW, RUNNING, STOPPING, STOPPED }

    private final GatewayConfig config;
    private final GatewayListener listener;
    private final ExecutorService connectionThreads =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("cardrail-gw-conn-", 0).factory());
    private final ExecutorService requestThreads =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("cardrail-gw-req-", 0).factory());
    private final GatewayContext context;
    private final Set<GatewayConnection> connections = ConcurrentHashMap.newKeySet();

    private volatile State state = State.NEW;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public IsoGateway(GatewayConfig config, IsoRequestHandler handler) {
        this(config, handler, GatewayListener.NOOP);
    }

    public IsoGateway(GatewayConfig config, IsoRequestHandler handler, GatewayListener listener) {
        this.config = Objects.requireNonNull(config, "config");
        this.listener = new SafeListener(Objects.requireNonNull(listener, "listener"));
        this.context = new GatewayContext(config, Objects.requireNonNull(handler, "handler"), this.listener,
                new IsoCodec(), requestThreads, Thread.ofVirtual().name("cardrail-gw-handler-", 0).factory(),
                () -> state != State.RUNNING);
    }

    /**
     * Binds the listening socket and starts accepting. Returns once the port is bound.
     *
     * @throws IllegalStateException if already started
     * @throws IOException           if the address cannot be bound
     */
    public synchronized void start() throws IOException {
        if (state != State.NEW) {
            throw new IllegalStateException("gateway already started");
        }
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(config.bindAddress(), config.port()), config.backlog());
        } catch (IOException e) {
            ss.close();
            throw e;
        }
        serverSocket = ss;
        state = State.RUNNING;
        acceptThread = Thread.ofVirtual().name("cardrail-gw-accept").start(this::acceptLoop);
        LOG.log(INFO, "ISO 8583 gateway listening on {0}", ss.getLocalSocketAddress());
    }

    /** The bound port, which differs from the configured one when that was 0. */
    public synchronized int port() {
        if (serverSocket == null) {
            throw new IllegalStateException("gateway not started");
        }
        return serverSocket.getLocalPort();
    }

    public int activeConnections() {
        return connections.size();
    }

    private void acceptLoop() {
        int idleTimeoutMillis = (int) Math.min(Integer.MAX_VALUE, Math.max(1, config.idleTimeout().toMillis()));
        while (state == State.RUNNING) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                if (state != State.RUNNING) {
                    return;
                }
                LOG.log(WARNING, "accept failed: {0}", e.toString());
                // Failures such as running out of file descriptors repeat until one is released;
                // back off instead of spinning on them.
                try {
                    Thread.sleep(10);
                } catch (InterruptedException ie) {
                    return;
                }
                continue;
            }
            SocketAddress remote = socket.getRemoteSocketAddress();
            if (connections.size() >= config.maxConnections()) {
                LOG.log(WARNING, "rejecting {0}: {1} connections open", remote, connections.size());
                closeQuietly(socket);
                listener.connectionRejected(remote);
                continue;
            }
            GatewayConnection connection;
            try {
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.setSoTimeout(idleTimeoutMillis);
                connection = new GatewayConnection(socket, context, connections::remove);
            } catch (IOException e) {
                LOG.log(DEBUG, "setting up {0} failed: {1}", remote, e.toString());
                closeQuietly(socket);
                continue;
            }
            register(connection);
        }
    }

    private void register(GatewayConnection connection) {
        connections.add(connection);
        listener.connectionOpened(connection.remote());
        try {
            connectionThreads.execute(connection::run);
        } catch (RejectedExecutionException e) {
            connection.close(CloseReason.SHUTDOWN);
        }
    }

    /**
     * Stops accepting, stops reading from every connection, waits up to {@code drainTimeout} for
     * requests already read to be answered, then closes all connections. Requests still being
     * processed at the deadline are abandoned and their handler threads interrupted.
     *
     * @return {@code true} if everything drained before the deadline
     */
    public boolean shutdown(Duration drainTimeout) throws InterruptedException {
        Objects.requireNonNull(drainTimeout, "drainTimeout");
        synchronized (this) {
            if (state == State.NEW) {
                state = State.STOPPED;
                return true;
            }
            if (state != State.RUNNING) {
                return connectionThreads.awaitTermination(drainTimeout.toNanos(), TimeUnit.NANOSECONDS);
            }
            state = State.STOPPING;
            closeQuietly(serverSocket);
        }
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        acceptThread.join(drainTimeout);
        for (GatewayConnection c : connections) {
            c.stopReading();
        }
        connectionThreads.shutdown();
        boolean drained = connectionThreads.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        requestThreads.shutdown();
        drained &= requestThreads.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
        if (!drained) {
            LOG.log(WARNING, "gateway did not drain within {0}; closing {1} connections",
                    drainTimeout, connections.size());
            for (GatewayConnection c : connections) {
                c.close(CloseReason.SHUTDOWN);
            }
            connectionThreads.shutdownNow();
            requestThreads.shutdownNow();
        }
        state = State.STOPPED;
        LOG.log(INFO, "ISO 8583 gateway stopped");
        return drained;
    }

    /** {@link #shutdown(Duration)} with {@link GatewayConfig#drainTimeout()}. */
    @Override
    public void close() {
        try {
            shutdown(config.drainTimeout());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception e) {
            LOG.log(DEBUG, "close failed: {0}", e.toString());
        }
    }
}
