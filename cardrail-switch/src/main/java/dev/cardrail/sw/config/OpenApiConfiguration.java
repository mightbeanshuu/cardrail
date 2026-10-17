package dev.cardrail.sw.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI cardrailOpenApi() {
        return new OpenAPI().info(new Info()
                .title("cardrail switch")
                .version("0.1.0")
                .description("Authorization, reversal and capture for the cardrail learning-grade card switch. "
                        + "Business declines are HTTP 200 with an ISO 8583 response code; HTTP errors mean the "
                        + "request itself could not be processed."));
    }
}
