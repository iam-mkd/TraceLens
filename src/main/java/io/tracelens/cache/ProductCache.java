package io.tracelens.cache;

import java.util.Optional;

import io.micrometer.core.instrument.MeterRegistry;
import io.tracelens.configuration.CacheProperties;
import io.tracelens.product.DependencyUnavailableException;
import io.tracelens.product.Product;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ProductCache {
    private static final String PREFIX = "tracelens:product:v1:";
    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final CacheProperties properties;
    private final MeterRegistry registry;

    public ProductCache(StringRedisTemplate redis, JsonMapper json,
                        CacheProperties properties, MeterRegistry registry) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
        this.registry = registry;
    }

    public Optional<Product> findById(long id) {
        try {
            String value = redis.opsForValue().get(PREFIX + id);
            if (value == null) {
                registry.counter("tracelens.cache.lookups", "outcome", "miss").increment();
                return Optional.empty();
            }
            Product product = json.readValue(value, Product.class);
            if (product == null || product.id() != id || product.name() == null
                    || product.price() == null || product.currency() == null) {
                throw new IllegalArgumentException("Invalid cached product");
            }
            registry.counter("tracelens.cache.lookups", "outcome", "hit").increment();
            return Optional.of(product);
        } catch (DataAccessException | JacksonException | IllegalArgumentException ex) {
            registry.counter("tracelens.cache.lookups", "outcome", "error").increment();
            throw new DependencyUnavailableException("redis", ex);
        }
    }

    public void put(Product product) {
        try {
            // One Redis SET with expiration; never a separate SET then EXPIRE race.
            redis.opsForValue().set(PREFIX + product.id(), json.writeValueAsString(product), properties.ttl());
            registry.counter("tracelens.cache.writes", "outcome", "success").increment();
        } catch (DataAccessException | JacksonException ex) {
            registry.counter("tracelens.cache.writes", "outcome", "error").increment();
            throw new DependencyUnavailableException("redis", ex);
        }
    }

    /** Evicts only a TraceLens-owned product key. */
    public void evict(long id) {
        try {
            redis.delete(PREFIX + id);
        } catch (DataAccessException ex) {
            throw new DependencyUnavailableException("redis", ex);
        }
    }
}
