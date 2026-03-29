package j143.github.pushgateway;

import j143.github.push.proto.ServerHeartbeat;
import j143.github.push.proto.ServerToClient;
import j143.github.pushgateway.model.ClientSession;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that prove {@link ClientSession} serializes writes to the gRPC
 * {@link StreamObserver} so that concurrent callers cannot corrupt the stream.
 *
 * <p>Critical flaw being fixed: gRPC's {@code StreamObserver} is not
 * thread-safe. The original code had two independent call sites:
 * <ul>
 *   <li>Thread A (gRPC worker): {@code outbound.onNext(control)} in
 *       {@code handleHello}</li>
 *   <li>Thread B (Spring Scheduler): {@code session.getOutbound().onNext(push)}
 *       in {@code dispatchAll()}</li>
 * </ul>
 * Concurrent calls threw {@code IllegalStateException: call is closed} and
 * permanently killed the user's connection. The fix adds a
 * {@link java.util.concurrent.locks.ReentrantLock} inside {@link ClientSession}
 * and routes all writes through {@link ClientSession#writeToClient}.
 */
class ClientSessionThreadSafetyTest {

    /**
     * A recording StreamObserver that detects concurrent writes.
     * Uses a {@link CountDownLatch} to create a deterministic overlap window:
     * the first writer signals {@code enteredOnNext} before completing, so a
     * second writer can attempt entry at the worst possible moment.
     */
    private static class RecordingObserver implements StreamObserver<ServerToClient> {
        final List<ServerToClient> received = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger concurrentWriteCount = new AtomicInteger(0);
        final AtomicInteger inProgress = new AtomicInteger(0);

        @Override
        public void onNext(ServerToClient value) {
            int concurrent = inProgress.incrementAndGet();
            if (concurrent > 1) {
                concurrentWriteCount.incrementAndGet();
            }
            received.add(value);
            inProgress.decrementAndGet();
        }

        @Override public void onError(Throwable t) {}
        @Override public void onCompleted() {}
    }

    @Test
    void writeToClient_serializesWrites_noConcurrentOnNextCalls()
            throws InterruptedException {
        RecordingObserver obs = new RecordingObserver();
        ClientSession session = new ClientSession("user-ts", "dev-1", obs, 0L);

        ServerToClient msg = ServerToClient.newBuilder()
                .setHeartbeat(ServerHeartbeat.getDefaultInstance())
                .build();

        int threads = 10;
        int writesPerThread = 20;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < writesPerThread; j++) {
                        session.writeToClient(msg);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        exec.shutdown();

        assertThat(obs.received).hasSize(threads * writesPerThread);
        assertThat(obs.concurrentWriteCount.get())
                .as("writeToClient must prevent concurrent onNext calls – "
                        + "gRPC StreamObserver is not thread-safe")
                .isEqualTo(0);
    }

    @Test
    void completeStream_isSerializedWithPrecedingWrites() throws InterruptedException {
        RecordingObserver obs = new RecordingObserver();
        ClientSession session = new ClientSession("user-ts2", "dev-2", obs, 0L);

        ServerToClient msg = ServerToClient.newBuilder()
                .setHeartbeat(ServerHeartbeat.getDefaultInstance())
                .build();

        // Thread A writes; Thread B completes – must not observe concurrent access
        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch bothDone     = new CountDownLatch(2);

        Thread writerThread = new Thread(() -> {
            try {
                writeStarted.countDown();
                session.writeToClient(msg);
            } finally {
                bothDone.countDown();
            }
        });
        Thread completerThread = new Thread(() -> {
            try {
                writeStarted.await();
                session.completeStream();
            } catch (InterruptedException ignored) {
            } finally {
                bothDone.countDown();
            }
        });

        writerThread.start();
        completerThread.start();
        assertThat(bothDone.await(5, TimeUnit.SECONDS)).isTrue();

        // If both calls were serialized, concurrent writes should never be > 1
        assertThat(obs.concurrentWriteCount.get())
                .as("completeStream must be serialized with concurrent writeToClient calls")
                .isEqualTo(0);
    }
}
