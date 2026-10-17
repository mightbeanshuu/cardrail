package dev.cardrail.sw.adapter.dev;

import dev.cardrail.sw.port.CardTokenizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selected with {@code cardrail.tokenizer=dev}; the default is the vault. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cardrail.tokenizer", havingValue = "dev")
class DevTokenizerConfiguration {

    @Bean
    CardTokenizer devCardTokenizer(@Value("${cardrail.dev.tokenizer.key}") String key) {
        return new DevCardTokenizer(key);
    }
}
