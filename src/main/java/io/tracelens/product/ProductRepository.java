package io.tracelens.product;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.tracelens.lab.LabContext;
import io.tracelens.lab.LabObservation;
import org.springframework.stereotype.Repository;

/** Counts only this repository's product SELECTs, never health checks or migrations. */
@Repository
public class ProductRepository {
    private final DataSource dataSource;
    private final Counter attempts;
    private final Counter successes;
    private final Counter failures;
    private final Timer acquisitionTime;
    private final Timer queryTime;
    private final AtomicInteger active = new AtomicInteger();

    public ProductRepository(DataSource dataSource, MeterRegistry registry) {
        this.dataSource = dataSource;
        attempts = registry.counter("tracelens.product.sql.attempts");
        successes = registry.counter("tracelens.product.sql.successes");
        failures = registry.counter("tracelens.product.sql.failures");
        acquisitionTime = registry.timer("tracelens.product.sql.connection.acquire");
        queryTime = registry.timer("tracelens.product.sql.duration");
        Gauge.builder("tracelens.product.sql.active", active, AtomicInteger::get)
                .description("Client-side product SQL executions currently outstanding")
                .register(registry);
    }

    public Optional<Product> findById(long id) {
        LabObservation observation = LabContext.current();
        String sql = observation == null
                ? "SELECT id, name, price, currency FROM products WHERE id = ?"
                : "SELECT id, name, price, currency, pg_sleep(? / 1000.0) FROM products WHERE id = ?";
        try (Connection connection = acquire();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            if (observation == null) statement.setLong(1, id);
            else { statement.setInt(1, observation.delayMillis); statement.setLong(2, id); }
            statement.setQueryTimeout(2);
            attempts.increment();
            active.incrementAndGet();
            if (observation != null) observation.sqlStart();
            long started = System.nanoTime();
            Timer.Sample sample = Timer.start();
            try {
                Optional<Product> result;
                try (ResultSet rows = statement.executeQuery()) {
                    result = rows.next()
                            ? Optional.of(new Product(rows.getLong("id"), rows.getString("name"),
                                    rows.getBigDecimal("price"), rows.getString("currency")))
                            : Optional.empty();
                }
                successes.increment();
                if (observation != null) observation.sqlSuccesses.increment();
                return result;
            } catch (SQLException ex) {
                failures.increment();
                if (observation != null) observation.sqlFailures.increment();
                throw ex;
            } finally {
                active.decrementAndGet();
                if (observation != null) {
                    observation.activeSql.decrementAndGet();
                    observation.queryNanos.add(System.nanoTime() - started);
                }
                sample.stop(queryTime);
            }
        } catch (SQLException ex) {
            throw new DependencyUnavailableException("postgresql", ex);
        }
    }

    private Connection acquire() throws SQLException {
        Timer.Sample sample = Timer.start();
        long started = System.nanoTime();
        try {
            return dataSource.getConnection();
        } finally {
            sample.stop(acquisitionTime);
            LabObservation observation = LabContext.current();
            if (observation != null) observation.acquisitionNanos.add(System.nanoTime() - started);
        }
    }
}
