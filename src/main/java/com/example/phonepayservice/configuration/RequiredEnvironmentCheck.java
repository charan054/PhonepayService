package com.example.phonepayservice.configuration;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spring Boot leaves an unresolved ${PLACEHOLDER} as literal text rather than failing, so a missing secret only
 * shows up later as a confusing downstream error (MySQL "Access denied", or the bank rejecting every call with a
 * 401). This fails at startup instead, with a message that says exactly what to set.
 * <p>
 * Replaces the earlier, single-purpose DbPasswordCheck now that the bank's service key needs the same treatment.
 */
public class RequiredEnvironmentCheck implements EnvironmentPostProcessor, Ordered {

    // property name -> (environment variable to set, one-line description of what it protects)
    private static final Map<String, String[]> REQUIRED = new LinkedHashMap<>();
    static {
        REQUIRED.put("spring.datasource.password", new String[]{"DB_PASSWORD", "the MySQL password"});
        REQUIRED.put("bank.service.api-key", new String[]{"SERVICE_API_KEY", "the key the Bank app trusts this service with; it must match the SERVICE_API_KEY set on the Bank app too"});
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (Map.Entry<String, String[]> entry : REQUIRED.entrySet()) {
            try {
                environment.getProperty(entry.getKey());
            } catch (IllegalArgumentException e) {
                String envVar = entry.getValue()[0];
                String description = entry.getValue()[1];
                throw new IllegalStateException(
                        entry.getKey() + " is not configured (" + description + "). Either set a " + envVar
                                + " environment variable (IntelliJ: Run > Edit Configurations > Environment variables), "
                                + "or add " + envVar + "=<value> to a .env file in the project root (copy .env.example).", e);
            }
        }
    }

    // Run after the application.properties files have been loaded.
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
