package io.tracelens.configuration;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("tracelens.cache")
public record CacheProperties(@NotNull Duration ttl) {
    @AssertTrue(message = "cache TTL must be between 1 millisecond and 1 day")
    public boolean isTtlWithinBounds() {
        return ttl != null && ttl.compareTo(Duration.ofMillis(1)) >= 0
                && ttl.compareTo(Duration.ofDays(1)) <= 0;
    }
}
