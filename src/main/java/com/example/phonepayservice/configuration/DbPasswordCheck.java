package com.example.phonepayservice.configuration;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

// Spring Boot leaves an unresolved ${DB_PASSWORD} as literal text and sends it to MySQL as the password, which
// ends in a confusing "Access denied". Failing here, before any connection is attempted, says what is actually wrong.
public class DbPasswordCheck implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        try {
            environment.getProperty("spring.datasource.password");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "The database password is not configured. spring.datasource.password refers to ${DB_PASSWORD}, "
                            + "which has no value. Either set a DB_PASSWORD environment variable "
                            + "(IntelliJ: Run > Edit Configurations > Environment variables), or create a file named "
                            + ".env in the project root containing the line DB_PASSWORD=<your MySQL password> "
                            + "(copy .env.example).", e);
        }
    }

    // Run after the application.properties files have been loaded.
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
