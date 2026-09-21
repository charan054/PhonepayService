package com.example.phonepayservice.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Boot does NOT fail on an unresolved ${DB_PASSWORD}; it silently sends the literal text "${DB_PASSWORD}"
 * to MySQL, which then reports a confusing "Access denied". DbPasswordCheck turns that into a clear startup error.
 * MockEnvironment is used (not StandardEnvironment) so a DB_PASSWORD set on the developer's machine can't affect these tests.
 */
class DbPasswordCheckTest {

    private final DbPasswordCheck check = new DbPasswordCheck();

    @Test
    void unresolvedPlaceholder_failsStartupWithAClearMessage() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.password", "${DB_PASSWORD}");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> check.postProcessEnvironment(env, null));

        assertTrue(ex.getMessage().contains("DB_PASSWORD"), ex.getMessage());
        assertTrue(ex.getMessage().contains(".env"), "message should tell the developer about the .env file: " + ex.getMessage());
    }

    @Test
    void placeholderThatResolves_isAccepted() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "${DB_PASSWORD}")
                .withProperty("DB_PASSWORD", "s3cret");

        assertDoesNotThrow(() -> check.postProcessEnvironment(env, null));
    }

    @Test
    void emptyPassword_isAccepted_asUsedByTheTestProfile() {
        MockEnvironment env = new MockEnvironment().withProperty("spring.datasource.password", "");

        assertDoesNotThrow(() -> check.postProcessEnvironment(env, null));
    }

    @Test
    void noPasswordProperty_isAccepted() {
        assertDoesNotThrow(() -> check.postProcessEnvironment(new MockEnvironment(), null));
    }
}
