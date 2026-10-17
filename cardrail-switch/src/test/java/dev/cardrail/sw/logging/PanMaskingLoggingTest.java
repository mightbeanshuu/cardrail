package dev.cardrail.sw.logging;

import dev.cardrail.sw.application.AuthorizeService;
import dev.cardrail.sw.support.CardrailIntegrationTest;
import dev.cardrail.sw.support.RequestBuilder;
import dev.cardrail.sw.support.TestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@CardrailIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PanMaskingLoggingTest {

    private static final Logger log = LoggerFactory.getLogger(PanMaskingLoggingTest.class);
    private static final String PAN = "5555555555554444";

    @Autowired TestFixtures fixtures;
    @Autowired AuthorizeService authorize;

    @Test
    void panInAMessageOrAnExceptionIsMaskedInTheConsoleOutput(CapturedOutput output) {
        log.info("careless log line with card {} in it", PAN);
        log.warn("failure", new IllegalArgumentException("bad input " + PAN));

        assertThat(output.getOut()).doesNotContain(PAN)
                .contains("careless log line with card 555555******4444 in it")
                .contains("bad input 555555******4444");
    }

    @Test
    void aWholeAuthorizationLogsNoPan(CapturedOutput output) {
        fixtures.reset();
        authorize.authorize(RequestBuilder.purchase(PAN, 100).build());
        authorize.authorize(RequestBuilder.purchase("5555555555554445", 100).build());

        assertThat(output.getOut()).doesNotContain(PAN).doesNotContain("5555555555554445");
    }

    @Test
    void structuredJsonLogsAreMaskedToo() {
        JsonWriter<Object> writer = JsonWriter.of(members -> {
            members.add("message", "card " + PAN);
            members.add("mdc", Map.of("note", PAN));
            new PanMaskingJsonMembersCustomizer().customize(members);
        });

        assertThat(writer.writeToString(new Object())).doesNotContain(PAN).contains("555555******4444");
    }
}
