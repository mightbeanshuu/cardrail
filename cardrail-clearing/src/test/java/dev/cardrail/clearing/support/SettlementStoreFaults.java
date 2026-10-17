package dev.cardrail.clearing.support;

import dev.cardrail.clearing.port.SettlementStore;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/** Makes the settlement job's chunk writer fail on a chosen chunk, to exercise restart. */
public class SettlementStoreFaults implements BeanPostProcessor {

    private final AtomicInteger failOnCall = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    public void failOnChunk(int chunk) {
        calls.set(0);
        failOnCall.set(chunk);
    }

    public void clear() {
        failOnCall.set(0);
        calls.set(0);
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof SettlementStore store)) {
            return bean;
        }
        return Proxy.newProxyInstance(SettlementStore.class.getClassLoader(), new Class<?>[] {SettlementStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addPartials") && failOnCall.get() > 0
                            && calls.incrementAndGet() == failOnCall.get()) {
                        throw new IllegalStateException("injected fault in chunk " + failOnCall.get());
                    }
                    try {
                        return method.invoke(store, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
