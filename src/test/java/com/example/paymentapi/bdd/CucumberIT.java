package com.example.paymentapi.bdd;

import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * JUnit 5 suite runner that discovers all Cucumber feature files under
 * {@code src/test/resources/features/} and executes them using the
 * {@code cucumber} engine.
 *
 * <p>Configuration (glue path, plugins, publish settings) is in
 * {@code src/test/resources/junit-platform.properties}.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
public class CucumberIT {
    // no body — discovery and execution are driven by the engine
}
