package com.example.phonepayservice.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MockEnvironment is used (not StandardEnvironment) so values set on the developer's own machine can't affect these tests.
 */
class RequiredEnvironmentCheckTest {

    private final RequiredEnvironmentCheck check = new RequiredEnvironmentCheck();

    @ParameterizedTest
    @ValueSource(strings = {"spring.datasource.password", "bank.service.api-key"})
    void unresolvedPlaceholder_failsStartupWithAClearMessage(String property) {
        MockEnvironment env = new MockEnvironment().withProperty(property, "${SOME_VAR}");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> check.postProcessEnvironment(env, null));

        assertTrue(ex.getMessage().contains(property), ex.getMessage());
        assertTrue(ex.getMessage().contains(".env"), "message should tell the developer about the .env file: " + ex.getMessage());
    }

    @Test
    void bothResolved_isAccepted() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "s3cret")
                .withProperty("bank.service.api-key", "service-key-value");

        assertDoesNotThrow(() -> check.postProcessEnvironment(env, null));
    }

    @Test
    void emptyPassword_isAccepted_asUsedByTheTestProfile() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "")
                .withProperty("bank.service.api-key", "test-service-key");

        assertDoesNotThrow(() -> check.postProcessEnvironment(env, null));
    }

    @Test
    void noPropertiesAtAll_isAccepted() {
        assertDoesNotThrow(() -> check.postProcessEnvironment(new MockEnvironment(), null));
    }
}
