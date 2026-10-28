package io.tracelens.lab;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import io.tracelens.cache.ProductCache;
import io.tracelens.product.DependencyUnavailableException;
import io.tracelens.product.Product;
import io.tracelens.product.ProductRepository;
import io.tracelens.product.ProductService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class SingleFlightTest {
    private final ProductCache cache = mock(ProductCache.class);
    private final ProductRepository repository = mock(ProductRepository.class);
    private final ProductService service = new ProductService(cache, repository);
    private final Product product = new Product(1, "Test", new BigDecimal("1.00"), "USD");
    private final LabObservation observation = new LabObservation(UUID.randomUUID().toString(), "mitigation", 8, 100);

    private Product lookup(long id) {
        LabContext.install(observation);
        try { return service.findById(id); }
        finally { LabContext.clear(); }
    }

    @Test
    void sameKeySharesOneLoadAndFailureAllowsNewLeader() throws Exception {
        when(cache.findById(anyLong())).thenReturn(Optional.empty());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findById(1)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout");
            throw new DependencyUnavailableException("postgresql", new RuntimeException("synthetic"));
        }).thenReturn(Optional.of(product));
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                Future<Product> leader = workers.submit(() -> lookup(1));
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                Future<Product> follower = workers.submit(() -> lookup(1));
                await().atMost(Duration.ofSeconds(3)).until(() -> observation.waiters.sum() == 1);
                release.countDown();
                assertThatThrownBy(leader::get).hasCauseInstanceOf(DependencyUnavailableException.class);
                assertThatThrownBy(follower::get).hasCauseInstanceOf(DependencyUnavailableException.class);
                assertThat(service.inFlightCount()).isZero();
                assertThat(lookup(1)).isEqualTo(product);
                verify(repository, times(2)).findById(1);
                verify(cache, times(1)).put(product);
                assertThat(service.inFlightCount()).isZero();
            } finally { release.countDown(); }
        }
    }

    @Test
    void differentKeysCanLoadConcurrently() throws Exception {
        when(cache.findById(anyLong())).thenReturn(Optional.empty());
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findById(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            bothEntered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout");
            return Optional.of(new Product(id, "Test", BigDecimal.ONE, "USD"));
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                Future<Product> one = workers.submit(() -> lookup(1));
                Future<Product> two = workers.submit(() -> lookup(2));
                assertThat(bothEntered.await(3, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                assertThat(one.get().id()).isEqualTo(1);
                assertThat(two.get().id()).isEqualTo(2);
                assertThat(service.inFlightCount()).isZero();
            } finally { release.countDown(); }
        }
    }

    @Test
    void interruptedFollowerDoesNotCancelLeader() throws Exception {
        when(cache.findById(anyLong())).thenReturn(Optional.empty());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findById(1)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout");
            return Optional.of(product);
        });
        AtomicBoolean interrupted = new AtomicBoolean();
        try (var workers = Executors.newSingleThreadExecutor()) {
            try {
                Future<Product> leader = workers.submit(() -> lookup(1));
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                Thread follower = Thread.ofPlatform().start(() -> {
                    try { lookup(1); }
                    catch (DependencyUnavailableException expected) { interrupted.set(Thread.currentThread().isInterrupted()); }
                });
                await().atMost(Duration.ofSeconds(3)).until(() -> observation.waiters.sum() == 1);
                follower.interrupt();
                follower.join(2000);
                assertThat(interrupted).isTrue();
                assertThat(leader.isDone()).isFalse();
                release.countDown();
                assertThat(leader.get()).isEqualTo(product);
                assertThat(service.inFlightCount()).isZero();
            } finally { release.countDown(); }
        }
    }

    @Test
    void timedOutFollowerLeavesSharedLoadRunning() throws Exception {
        when(cache.findById(anyLong())).thenReturn(Optional.empty());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.findById(1)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout");
            return Optional.of(product);
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                Future<Product> leader = workers.submit(() -> lookup(1));
                assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
                Future<Product> follower = workers.submit(() -> lookup(1));
                assertThatThrownBy(() -> follower.get(6, TimeUnit.SECONDS)).hasCauseInstanceOf(DependencyUnavailableException.class);
                assertThat(service.inFlightCount()).isEqualTo(1);
                assertThat(leader.isDone()).isFalse();
                release.countDown();
                assertThat(leader.get()).isEqualTo(product);
                assertThat(service.inFlightCount()).isZero();
            } finally { release.countDown(); }
        }
    }

    @Test
    void cacheWriteFailureReleasesFollowersAndAllowsRecovery() throws Exception {
        when(cache.findById(anyLong())).thenReturn(Optional.empty());
        when(repository.findById(1)).thenReturn(Optional.of(product));
        CountDownLatch writeStarted = new CountDownLatch(1), releaseWrite = new CountDownLatch(1);
        doAnswer(call -> {
            writeStarted.countDown();
            if (!releaseWrite.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("gate timeout");
            throw new DependencyUnavailableException("redis", new RuntimeException("synthetic"));
        }).doNothing().when(cache).put(product);
        try (var workers = Executors.newFixedThreadPool(2)) {
            try {
                Future<Product> leader = workers.submit(() -> lookup(1));
                assertThat(writeStarted.await(3, TimeUnit.SECONDS)).isTrue();
                Future<Product> follower = workers.submit(() -> lookup(1));
                await().atMost(Duration.ofSeconds(3)).until(() -> observation.waiters.sum() == 1);
                releaseWrite.countDown();
                assertThatThrownBy(leader::get).hasCauseInstanceOf(DependencyUnavailableException.class);
                assertThatThrownBy(follower::get).hasCauseInstanceOf(DependencyUnavailableException.class);
                assertThat(observation.writeFailures.sum()).isEqualTo(1);
                assertThat(service.inFlightCount()).isZero();
                assertThat(lookup(1)).isEqualTo(product);
                assertThat(service.inFlightCount()).isZero();
            } finally { releaseWrite.countDown(); }
        }
    }

    @Test
    void leaderRechecksCacheBeforeOrigin() {
        when(cache.findById(1)).thenReturn(Optional.empty()).thenReturn(Optional.of(product));
        assertThat(lookup(1)).isEqualTo(product);
        verifyNoInteractions(repository);
        assertThat(observation.secondaryHits.sum()).isEqualTo(1);
        assertThat(service.inFlightCount()).isZero();
    }

    @Test
    void distinguishesInitialAndSecondaryCacheReadErrors() {
        DependencyUnavailableException error = new DependencyUnavailableException("redis", new RuntimeException("synthetic"));
        when(cache.findById(1)).thenThrow(error).thenReturn(Optional.empty()).thenThrow(error);
        assertThatThrownBy(() -> lookup(1)).isSameAs(error);
        assertThat(observation.initialLookupErrors.sum()).isEqualTo(1);
        assertThatThrownBy(() -> lookup(1)).isSameAs(error);
        assertThat(observation.initialMisses.sum()).isEqualTo(1);
        assertThat(observation.secondaryReads.sum()).isEqualTo(1);
        assertThat(observation.secondaryLookupErrors.sum()).isEqualTo(1);
        assertThat(service.inFlightCount()).isZero();
        verifyNoInteractions(repository);
    }
}
