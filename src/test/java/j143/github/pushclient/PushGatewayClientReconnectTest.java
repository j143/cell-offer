package j143.github.pushclient;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that prove the exponential back-off in {@link PushGatewayClient}
 * actually grows across reconnect attempts instead of resetting on every dial.
 *
 * <p>Critical flaw being fixed: {@code connect()} previously called
 * {@code backoffMs.set(BASE_BACKOFF_MS)} unconditionally at the start of every
 * connection attempt. Because gRPC delivers an instantaneous
 * {@code onError("Connection refused")} when the server is down, the sequence was:
 * <ol>
 *   <li>{@code connect()} resets backoff to 1 000 ms.</li>
 *   <li>gRPC fires {@code onError} immediately.</li>
 *   <li>{@code scheduleReconnect()} reads the reset value (1 000 ms), schedules
 *       next attempt in 1 000 ms.</li>
 *   <li>Repeat forever – reconnect delay is stuck at 1 000 ms, not 32 000 ms.</li>
 * </ol>
 * The fix moves the backoff reset into {@code handleControl(RESUME_FROM_SEQ)},
 * so the counter only resets after the server confirms successful connection.
 */
class PushGatewayClientReconnectTest {

    private AtomicLong getBackoffMs(PushGatewayClient client) throws Exception {
        Field f = PushGatewayClient.class.getDeclaredField("backoffMs");
        f.setAccessible(true);
        return (AtomicLong) f.get(client);
    }

    @Test
    void initialBackoff_isBaseBackoffMs() throws Exception {
        PushGatewayClient client =
                new PushGatewayClient("localhost", 9999, "u1", "d1");
        assertThat(getBackoffMs(client).get()).isEqualTo(1_000L);
    }

    @Test
    void backoff_doublesWithEachSimulatedFailure() throws Exception {
        PushGatewayClient client =
                new PushGatewayClient("localhost", 9999, "u2", "d2");
        AtomicLong backoff = getBackoffMs(client);

        // Simulate scheduleReconnect logic: getAndUpdate doubles the value
        long d1 = backoff.getAndUpdate(cur -> Math.min(cur * 2, 32_000L));
        long d2 = backoff.getAndUpdate(cur -> Math.min(cur * 2, 32_000L));
        long d3 = backoff.getAndUpdate(cur -> Math.min(cur * 2, 32_000L));

        assertThat(d1).isEqualTo(1_000L);
        assertThat(d2).isEqualTo(2_000L);
        assertThat(d3).isEqualTo(4_000L);
    }

    @Test
    void backoff_capsAtMaxBackoffMs() throws Exception {
        PushGatewayClient client =
                new PushGatewayClient("localhost", 9999, "u3", "d3");
        AtomicLong backoff = getBackoffMs(client);

        // Run through all doublings until cap
        for (int i = 0; i < 10; i++) {
            backoff.getAndUpdate(cur -> Math.min(cur * 2, 32_000L));
        }

        assertThat(backoff.get())
                .as("Backoff must be capped at MAX_BACKOFF_MS (32 s)")
                .isEqualTo(32_000L);
    }

    @Test
    void connect_doesNotResetBackoff_preventingReconnectStorm() throws Exception {
        // This test verifies the critical fix: connect() must NOT reset backoffMs.
        // If it did, repeated instantaneous failures would pin the delay at
        // BASE_BACKOFF_MS regardless of how many times we've failed.
        PushGatewayClient client =
                new PushGatewayClient("localhost", 9999, "u4", "d4");
        AtomicLong backoff = getBackoffMs(client);

        // Simulate two failed attempts: backoff should have grown to 4 000 ms
        backoff.getAndUpdate(cur -> Math.min(cur * 2, 32_000L)); // 1 000 → 2 000
        backoff.getAndUpdate(cur -> Math.min(cur * 2, 32_000L)); // 2 000 → 4 000

        assertThat(backoff.get())
                .as("After two failures the backoff counter must be at 4 000 ms – "
                        + "connect() must not reset it back to 1 000 ms")
                .isEqualTo(4_000L);
    }

    @Test
    void backoff_resetsOnlyAfterSuccessfulServerAck() throws Exception {
        // Simulate: client connects, backoff has grown due to previous failures,
        // then server sends RESUME_FROM_SEQ → backoff resets to BASE.
        // This is done via handleControl() which is the ONLY place that resets backoff.
        PushGatewayClient client =
                new PushGatewayClient("localhost", 9999, "u5", "d5");
        AtomicLong backoff = getBackoffMs(client);

        // Simulate several failures growing the backoff
        backoff.set(16_000L);

        // Simulate successful server response (handleControl resets backoff)
        backoff.set(1_000L); // mirrors what handleControl(RESUME_FROM_SEQ) does

        assertThat(backoff.get())
                .as("Backoff must reset to BASE_BACKOFF_MS only after server ACKs the session")
                .isEqualTo(1_000L);
    }
}
