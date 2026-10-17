package dev.cardrail.gateway;

import dev.cardrail.iso8583.IsoCodec;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.function.BooleanSupplier;

/** What every connection of one gateway shares. */
record GatewayContext(
        GatewayConfig config,
        IsoRequestHandler handler,
        GatewayListener listener,
        IsoCodec codec,
        ExecutorService requestExecutor,
        ThreadFactory handlerThreads,
        BooleanSupplier stopping) {
}
