package dev.cardrail.gateway;

import dev.cardrail.iso8583.Mti;

import java.net.SocketAddress;

/** Keeps a faulty listener from breaking the connection or request that triggered the hook. */
final class SafeListener implements GatewayListener {

    private static final System.Logger LOG = System.getLogger(SafeListener.class.getName());

    private final GatewayListener delegate;

    SafeListener(GatewayListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void connectionOpened(SocketAddress remote) {
        try {
            delegate.connectionOpened(remote);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "GatewayListener.connectionOpened threw", e);
        }
    }

    @Override
    public void connectionRejected(SocketAddress remote) {
        try {
            delegate.connectionRejected(remote);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "GatewayListener.connectionRejected threw", e);
        }
    }

    @Override
    public void connectionClosed(SocketAddress remote, CloseReason reason) {
        try {
            delegate.connectionClosed(remote, reason);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "GatewayListener.connectionClosed threw", e);
        }
    }

    @Override
    public void requestCompleted(Mti requestMti, String responseCode, long nanos) {
        try {
            delegate.requestCompleted(requestMti, responseCode, nanos);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "GatewayListener.requestCompleted threw", e);
        }
    }
}
