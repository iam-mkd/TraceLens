package io.tracelens.configuration;

import java.time.Duration;
import java.util.stream.Stream;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;

class CachePropertiesTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();

    @AfterAll
    static void closeFactory() {
        FACTORY.close();
    }

    static Stream<Duration> invalidTtls() {
        return Stream.of(null, Duration.ZERO, Duration.ofSeconds(-1),
                Duration.ofNanos(999999), Duration.ofDays(1).plusMillis(1));
    }

    static Stream<Duration> validTtls() {
        return Stream.of(Duration.ofMillis(1), Duration.ofSeconds(300), Duration.ofDays(1));
    }

    @ParameterizedTest
    @MethodSource("invalidTtls")
    void rejectsNonExpiringOrUnsupportedTtls(Duration ttl) {
        assertThat(FACTORY.getValidator().validate(new CacheProperties(ttl))).isNotEmpty();
    }

    @ParameterizedTest
    @MethodSource("validTtls")
    void acceptsSupportedTtlBounds(Duration ttl) {
        assertThat(FACTORY.getValidator().validate(new CacheProperties(ttl))).isEmpty();
    }
}
