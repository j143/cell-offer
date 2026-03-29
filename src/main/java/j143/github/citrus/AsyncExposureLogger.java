package j143.github.citrus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking, bounded exposure event logger.
 *
 * <p>Every call to {@link ExperimentClient#getIntParam} or
 * {@link ExperimentClient#getLongParam} that resolves an active experiment logs
 * an {@link ExposureEvent} by calling {@link #logExposure}. To guarantee that
 * {@code getParam()} never blocks the calling thread, events are placed on an
 * in-memory {@link ArrayBlockingQueue} via the non-blocking {@code offer()}
 * method. A single daemon thread flushes the queue in the background.
 *
 * <p><strong>Back-pressure contract:</strong> if the queue is full (e.g. the
 * flusher fell behind during a traffic spike), the event is silently dropped
 * and {@link #droppedExposures} is incremented. Monitor this counter – a
 * sustained non-zero value indicates that the queue capacity or flush rate needs
 * to be tuned.
 *
 * <p>For V1 the flusher writes JSON lines to the application logger on the
 * {@code citrus.exposure} category. In a production setup, replace
 * {@link #writeToDisk} with a batched Kafka/Kinesis producer.
 */
@Component
public class AsyncExposureLogger {

    private static final Logger log = LoggerFactory.getLogger("citrus.exposure");

    static final int DEFAULT_QUEUE_CAPACITY = 10_000;

    private final ArrayBlockingQueue<ExposureEvent> queue;
    private final AtomicLong droppedExposures = new AtomicLong(0);

    /** Creates a logger with the default queue capacity of {@value DEFAULT_QUEUE_CAPACITY}. */
    public AsyncExposureLogger() {
        this(DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * Creates a logger with the given queue capacity.
     * Exposed for testing (use a small capacity to trigger drop behaviour).
     */
    AsyncExposureLogger(int queueCapacity) {
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        Thread flusher = new Thread(this::flushLoop, "exposure-flusher");
        flusher.setDaemon(true);
        flusher.start();
    }

    /**
     * Enqueues an exposure event without blocking.
     *
     * <p>If the queue is full the event is dropped and
     * {@link #getDroppedExposures} is incremented.
     */
    public void logExposure(String unitId, String experimentName, String variant) {
        ExposureEvent event = new ExposureEvent(
                System.currentTimeMillis(), unitId, experimentName, variant);
        if (!queue.offer(event)) {
            droppedExposures.incrementAndGet();
        }
    }

    /** Returns the cumulative number of exposure events dropped due to a full queue. */
    public long getDroppedExposures() {
        return droppedExposures.get();
    }

    // -----------------------------------------------------------------------

    private void flushLoop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ExposureEvent event = queue.take(); // blocks until an event is available
                writeToDisk(event);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Writes a single exposure event to the log.
     * Swap this implementation with a batched producer (Kafka/Kinesis) when needed.
     */
    void writeToDisk(ExposureEvent event) {
        log.info(event.toString());
    }
}
