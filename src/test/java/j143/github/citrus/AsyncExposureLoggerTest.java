package j143.github.citrus;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the non-blocking and drop-counter guarantees of {@link AsyncExposureLogger}.
 */
class AsyncExposureLoggerTest {

    @Test
    void logExposure_doesNotBlockCallingThread() throws InterruptedException {
        // A queue capacity of 1 ensures the second offer hits the full-queue path.
        AsyncExposureLogger logger = new AsyncExposureLogger(1);

        // Fill the queue immediately before the flusher has a chance to drain it.
        // Both calls must return without blocking.
        AtomicInteger completed = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            logger.logExposure("u1", "Exp", "control");
            completed.incrementAndGet();
            done.countDown();
        });
        Thread t2 = new Thread(() -> {
            logger.logExposure("u2", "Exp", "treatment");
            completed.incrementAndGet();
            done.countDown();
        });

        t1.start();
        t2.start();

        // Both threads must finish in well under 1 s even if one event is dropped.
        assertThat(done.await(2, TimeUnit.SECONDS))
                .as("logExposure must not block the calling thread")
                .isTrue();
        assertThat(completed.get()).isEqualTo(2);
    }

    @Test
    void logExposure_incrementsDropCounter_whenQueueFull() throws InterruptedException {
        // Capacity 0 is not valid for ArrayBlockingQueue; use 1 and flood it.
        AsyncExposureLogger logger = new AsyncExposureLogger(1);

        // Send many events synchronously before the flusher can drain.
        for (int i = 0; i < 500; i++) {
            logger.logExposure("u-" + i, "FloodExp", "control");
        }

        // At least some events must have been dropped.
        assertThat(logger.getDroppedExposures())
                .as("dropped counter must increment when queue is full")
                .isGreaterThan(0);
    }

    @Test
    void logExposure_noDrops_whenQueueHasCapacity() throws InterruptedException {
        AsyncExposureLogger logger = new AsyncExposureLogger(10_000);

        for (int i = 0; i < 100; i++) {
            logger.logExposure("driver-" + i, "SmallExp", "control");
        }

        // Give the flusher a moment so it doesn't interfere with the assertion.
        // The key check is that zero drops occurred during normal operation.
        assertThat(logger.getDroppedExposures())
                .as("no events should be dropped when the queue has sufficient capacity")
                .isEqualTo(0);
    }
}
