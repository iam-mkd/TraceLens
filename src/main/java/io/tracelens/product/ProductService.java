package io.tracelens.product;

import io.tracelens.cache.ProductCache;
import io.tracelens.lab.LabContext;
import io.tracelens.lab.LabObservation;
import io.tracelens.telemetry.TraceSupport;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/** Single-flight is enabled only for the current lab cohort and only within this JVM. */
@Service
public class ProductService {
    private final ProductCache cache;
    private final ProductRepository repository;
    private final ConcurrentHashMap<Long, CompletableFuture<Product>> flights = new ConcurrentHashMap<>();

    public ProductService(ProductCache cache, ProductRepository repository) {
        this.cache = cache;
        this.repository = repository;
    }

    public Product findById(long id) {
        LabObservation observation = LabContext.current();
        Optional<Product> initial;
        try {
            initial = TraceSupport.cacheLookup("initial", () -> cache.findById(id));
        } catch (RuntimeException ex) {
            if (observation != null) observation.initialLookupErrors.increment();
            throw ex;
        }
        if (initial.isPresent()) {
            if (observation != null) observation.initialHits.increment();
            return initial.get();
        }
        if (observation != null) {
            observation.initialMisses.increment();
            observation.firstMissNanos.compareAndSet(0, System.nanoTime());
        }
        if (observation == null || !"mitigation".equals(observation.mode)) return load(id, observation);

        CompletableFuture<Product> created = new CompletableFuture<>();
        CompletableFuture<Product> existing = flights.putIfAbsent(id, created);
        if (existing != null) {
            observation.waiters.increment();
            Span waitSpan = TraceSupport.start("tracelens.coalescing.wait");
            try (Scope scope = waitSpan.makeCurrent()) {
                return existing.get(4, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                waitSpan.setStatus(StatusCode.ERROR);
                Thread.currentThread().interrupt();
                throw new DependencyUnavailableException("coalescing", ex);
            } catch (TimeoutException ex) {
                waitSpan.setStatus(StatusCode.ERROR);
                throw new DependencyUnavailableException("coalescing", ex);
            } catch (ExecutionException ex) {
                waitSpan.setStatus(StatusCode.ERROR);
                if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
                if (ex.getCause() instanceof Error error) throw error;
                throw new DependencyUnavailableException("coalescing", ex);
            } finally {
                waitSpan.end();
            }
        }
        Span leaderSpan = TraceSupport.start("tracelens.coalescing.leader");
        try (Scope scope = leaderSpan.makeCurrent()) {
            observation.secondaryReads.increment();
            Optional<Product> second;
            try {
                second = TraceSupport.cacheLookup("secondary", () -> cache.findById(id));
            } catch (RuntimeException ex) {
                observation.secondaryLookupErrors.increment();
                throw ex;
            }
            if (second.isPresent()) observation.secondaryHits.increment();
            Product product = second.orElseGet(() -> load(id, observation));
            created.complete(product);
            return product;
        } catch (RuntimeException | Error ex) {
            leaderSpan.setStatus(StatusCode.ERROR);
            created.completeExceptionally(ex);
            throw ex;
        } finally {
            flights.remove(id, created);
            leaderSpan.end();
        }
    }

    public int inFlightCount() { return flights.size(); }

    private Product load(long id, LabObservation observation) {
        Product product = TraceSupport.operation("tracelens.origin.load", () ->
                repository.findById(id).orElseThrow(ProductNotFoundException::new));
        try {
            TraceSupport.operation("tracelens.cache.write", () -> { cache.put(product); return null; });
        } catch (RuntimeException ex) {
            if (observation != null) observation.writeFailures.increment();
            throw ex;
        }
        if (observation != null) observation.refillNanos.compareAndSet(0, System.nanoTime());
        return product;
    }
}
