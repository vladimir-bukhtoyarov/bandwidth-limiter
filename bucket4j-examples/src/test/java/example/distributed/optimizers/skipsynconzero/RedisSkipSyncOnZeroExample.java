package example.distributed.optimizers.skipsynconzero;

import java.time.Duration;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.shaded.com.google.common.base.Charsets;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Snapshot;
import com.github.rollingmetrics.dropwizard.Dropwizard;
import com.github.rollingmetrics.histogram.OverflowResolver;
import com.github.rollingmetrics.histogram.hdr.RollingHdrHistogram;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.AsyncBucketProxy;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.synchronization.per_bucket.DefaultSynchronizationListener;
import io.github.bucket4j.distributed.proxy.synchronization.per_bucket.BucketSynchronization;
import io.github.bucket4j.distributed.proxy.synchronization.per_bucket.skiponzero.SkipSyncOnZeroBucketSynchronization;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;

public class RedisSkipSyncOnZeroExample {

    private static Logger logger = LoggerFactory.getLogger(RedisSkipSyncOnZeroExample.class);

    private static GenericContainer container;
    private static RedisClient redisClient;

    @BeforeClass
    public static void setup() {
        container = startRedisContainer();
        redisClient = createLettuceClient(container);
    }

    @AfterClass
    public static void shutdown() {
        if (redisClient != null) {
            redisClient.shutdown();
        }
        if (container != null) {
            container.close();
        }
    }

    private static RedisClient createLettuceClient(GenericContainer container) {
        String redisHost = container.getHost();
        Integer redisPort = container.getMappedPort(6379);
        String redisUrl = "redis://" + redisHost + ":" + redisPort;

        return RedisClient.create(redisUrl);
    }

    private static GenericContainer startRedisContainer() {
        GenericContainer genericContainer = new GenericContainer("redis:7.0.2").withExposedPorts(6379);
        genericContainer.start();
        return genericContainer;
    }

    @Test
    public void benchmarkSyncBucket() throws InterruptedException {
        Meter consumptionRate = new Meter();
        com.codahale.metrics.Timer latencyTimer = buildLatencyTimer();

        ProxyManager<String> proxyManager = Bucket4jLettuce.casBasedBuilder(redisClient)
            .expirationAfterWrite(ExpirationAfterWriteStrategy.none())
            .build()
            .withMapper(str -> str.getBytes(Charsets.UTF_8));
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(
                    Bandwidth.builder().capacity(50).refillGreedy(50, Duration.ofSeconds(60)).build())
                .build();

        AtomicLong totalMergedRequestCount = new AtomicLong();
        AtomicLong totalSkippedRequestCount = new AtomicLong();

        DefaultSynchronizationListener synchronizationListener = new DefaultSynchronizationListener();
        BucketSynchronization bucketSynchronization = new SkipSyncOnZeroBucketSynchronization(synchronizationListener, TimeMeter.SYSTEM_MILLISECONDS)
            .withListener(synchronizationListener);

        Bucket bucket = proxyManager.builder()
                .withSynchronization(bucketSynchronization)
                .build("13", () -> configuration);

        Timer statLogTimer = new Timer();
        statLogTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                System.out.println("Consumption rate " + consumptionRate.getOneMinuteRate() + " tokens/sec");
                System.out.println("Operations with bucket rate " + latencyTimer.getOneMinuteRate() + " ops/sec");
                System.out.println("Consumed since start " + consumptionRate.getCount() + " tokens");
                long skippedRequestCountSnapshot = synchronizationListener.getSkipCount();
                long mergedRequestCountSnapshot = synchronizationListener.getMergeCount();
                System.out.println("Synchronization stat: " +
                    "skipped=" + (skippedRequestCountSnapshot - totalSkippedRequestCount.get()) + " " +
                    "merged=" + (mergedRequestCountSnapshot - totalMergedRequestCount.get()));
                totalSkippedRequestCount.set(skippedRequestCountSnapshot);
                totalMergedRequestCount.set(mergedRequestCountSnapshot);
                Snapshot snapshot = latencyTimer.getSnapshot();
                System.out.println("Operations with bucket latency:" +
                        " mean=" + TimeUnit.NANOSECONDS.toMicros((long) snapshot.getMean()) + "micros" +
                        " median=" + TimeUnit.NANOSECONDS.toMicros((long)snapshot.getMedian()) + "micros" +
                        " max=" + TimeUnit.NANOSECONDS.toMillis(snapshot.getMax()) + "millis");
                System.out.println("---------------------------------------------");
            }
        }, 1_000, 1_000);

        int PARALLEL_THREADS = 100;
        // start
        for (int i = 0; i < PARALLEL_THREADS; i++) {
            Thread thread = new Thread(() -> {
                while (true) {
                    try (com.codahale.metrics.Timer.Context ctx = latencyTimer.time()) {
                        if (bucket.tryConsume(1)) {
                            consumptionRate.mark();
                        }
                    } catch (Throwable t) {
                        logger.error("Failed to consume tokens from bucket", t);
                    }
                }
            });
            thread.setName("Bucket consumer " + i);
            thread.start();
        }

        Thread.currentThread().join();
    }

    @Test
    public void benchmarkAsyncBucket() throws InterruptedException {
        Meter consumptionRate = new Meter();
        com.codahale.metrics.Timer latencyTimer = buildLatencyTimer();

        ProxyManager<String> proxyManager = Bucket4jLettuce.casBasedBuilder(redisClient)
            .expirationAfterWrite(ExpirationAfterWriteStrategy.none())
            .build()
            .withMapper(str -> str.getBytes(Charsets.UTF_8));
        BucketConfiguration configuration = BucketConfiguration.builder()
            .addLimit(limit -> limit.capacity(10).refillGreedy(10, Duration.ofSeconds(1)).initialTokens(0))
            .build();

        AtomicLong totalMergedRequestCount = new AtomicLong();
        AtomicLong totalSkippedRequestCount = new AtomicLong();
        DefaultSynchronizationListener synchronizationListener = new DefaultSynchronizationListener();
        BucketSynchronization bucketSynchronization = new SkipSyncOnZeroBucketSynchronization(synchronizationListener, TimeMeter.SYSTEM_MILLISECONDS)
            .withListener(synchronizationListener);

        AsyncBucketProxy bucket = proxyManager.asAsync().builder()
                .withSynchronization(bucketSynchronization)
                .build("13", () -> CompletableFuture.completedFuture(configuration));

        // We need a backpressure for ougoing work because it obviously that OOM can be happen in asycnhrouous bucket mode
        // when tasks incoming rate is greater then Hazelcast can process
        Semaphore semaphore = new Semaphore(20_00); // no more then 20_000 throttling requests in progress

        Timer statLogTimer = new Timer();
        statLogTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                System.out.println("Consumption rate " + consumptionRate.getOneMinuteRate() + " tokens/sec");
                System.out.println("Operations with bucket rate " + latencyTimer.getOneMinuteRate() + " ops/sec");
                System.out.println("Consumed since start " + consumptionRate.getCount() + " tokens");
                long skippedRequestCountSnapshot = synchronizationListener.getSkipCount();
                long mergedRequestCountSnapshot = synchronizationListener.getMergeCount();
                System.out.println("Synchronization stat: " +
                    "skipped=" + (skippedRequestCountSnapshot - totalSkippedRequestCount.get()) + " " +
                    "merged=" + (mergedRequestCountSnapshot - totalMergedRequestCount.get()));
                totalSkippedRequestCount.set(skippedRequestCountSnapshot);
                totalMergedRequestCount.set(mergedRequestCountSnapshot);
                Snapshot snapshot = latencyTimer.getSnapshot();
                System.out.println(
                        "Operations with bucket latency:" +
                        " mean=" + TimeUnit.NANOSECONDS.toMicros((long) snapshot.getMean()) + "micros" +
                        " median=" + TimeUnit.NANOSECONDS.toMicros((long)snapshot.getMedian()) + "micros" +
                        " max=" + TimeUnit.NANOSECONDS.toMillis(snapshot.getMax()) + "millis" +
                        " current_requests_in_progress=" + semaphore.availablePermits()
                );
                System.out.println("---------------------------------------------");
            }
        }, 1_000, 1_000);

        int PARALLEL_THREADS = 4;
        // start
        for (int i = 0; i < PARALLEL_THREADS; i++) {
            Thread thread = new Thread(() -> {
                while (true) {
                    try {
                        semaphore.acquire();
                        long currentTimeNanos = System.nanoTime();
                        bucket.tryConsume(1).whenComplete((consumed, error) -> {
                            long latencyNanos = System.nanoTime() - currentTimeNanos;
                            semaphore.release();
                            latencyTimer.update(latencyNanos, TimeUnit.NANOSECONDS);
                            if (error != null) {
                               logger.error("Failed to consume tokens from bucket", error);
                               return;
                            }
                            if (consumed) {
                               consumptionRate.mark();
                            }
                        });
                    } catch (Throwable t) {
                        semaphore.release();
                        logger.error("Failed to consume tokens from bucket", t);
                    }
                }
            });
            thread.setName("Bucket consumer " + i);
            thread.start();
        }

        Thread.currentThread().join();
    }

    private com.codahale.metrics.Timer buildLatencyTimer() {
        RollingHdrHistogram histogram = RollingHdrHistogram.builder()
                .withSignificantDigits(2)
                .resetReservoirPeriodicallyByChunks(Duration.ofSeconds(60), 3)
                .withHighestTrackableValue(1_000_000_000_000L, OverflowResolver.REDUCE_TO_HIGHEST_TRACKABLE)
                .build();
        return Dropwizard.toTimer(histogram);
    }

}
