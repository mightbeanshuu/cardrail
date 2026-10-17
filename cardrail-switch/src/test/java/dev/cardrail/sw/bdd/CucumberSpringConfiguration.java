package dev.cardrail.sw.bdd;

import dev.cardrail.sw.support.CardrailIntegrationTest;
import io.cucumber.spring.CucumberContextConfiguration;

/** Same configuration as the integration tests, so Cucumber reuses their cached Spring context. */
@CucumberContextConfiguration
@CardrailIntegrationTest
public class CucumberSpringConfiguration {
}
