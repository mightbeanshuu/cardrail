package dev.cardrail.sw.adapter.iso;

import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.application.ReverseService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cardrail.iso.enabled", havingValue = "true", matchIfMissing = true)
class IsoConfiguration {

    @Bean
    IsoGatewayLifecycle isoGateway(IsoProperties properties, AuthorizeService authorize, ReverseService reverse,
                                   MeterRegistry registry) {
        return new IsoGatewayLifecycle(properties, new SwitchIsoHandler(authorize, reverse),
                new MicrometerGatewayListener(registry));
    }
}
