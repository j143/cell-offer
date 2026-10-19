package j143.github.celloffer.push;

import com.google.protobuf.ByteString;
import j143.github.push.proto.PushServiceGrpc;
import j143.github.push.proto.SendPushRequest;
import j143.github.push.proto.SendPushResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Spring bean that forwards flushed offers to the PushGateway via the
 * {@code SendPush} unary gRPC RPC.
 *
 * <p>Replaces the {@code [RAMEN-SINK]} log statement in {@link
 * j143.github.celloffer.scheduler.OfferFlusher} with an actual gRPC call so that
 * the cell-offer service acts as a "producer" feeding the RAMEN-lite transport
 * layer.
 *
 * <p>The target address is configured via:
 * <pre>
 * push.gateway.host=localhost
 * push.gateway.grpc-port=9090
 * </pre>
 */
@Component
public class OfferPushClient {

    private static final Logger log = LoggerFactory.getLogger(OfferPushClient.class);

    private final String host;
    private final int    port;
    private final long   deadlineMs;
    private final MeterRegistry meterRegistry;
    private final Timer  sendTimer;

    private ManagedChannel channel;
    private PushServiceGrpc.PushServiceBlockingStub blockingStub;

    public OfferPushClient(
            @Value("${push.gateway.host:localhost}") String host,
            @Value("${push.gateway.grpc-port:9090}") int port,
            @Value("${push.gateway.send-deadline-ms:250}") long deadlineMs,
            MeterRegistry meterRegistry) {
        if (deadlineMs < 1) {
            throw new IllegalArgumentException("SendPush deadline must be positive");
        }
        this.host = host;
        this.port = port;
        this.deadlineMs = deadlineMs;
        this.meterRegistry = meterRegistry;
        this.sendTimer = Timer.builder("cell_offer_push_send_duration")
                .description("Duration of a PushGateway SendPush RPC")
                .register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        blockingStub = PushServiceGrpc.newBlockingStub(channel);
        log.info("[OfferPushClient] Initialized gRPC channel to {}:{} with {} ms deadline",
                host, port, deadlineMs);
    }

    @PreDestroy
    public void shutdown() throws InterruptedException {
        if (channel != null) {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Sends an offer payload to PushGateway for delivery to {@code driverId}.
     *
     * @param driverId   target driver (userId in PushGateway terms)
     * @param offerJson  JSON representation of the offer
     * @param ttlMillis  message time-to-live in milliseconds
     * @param priority   offer priority (higher = more urgent)
     * @return gateway acceptance outcome; never a driver acknowledgement
     */
    public SendOutcome sendOffer(String driverId, String offerJson, long ttlMillis, int priority) {
        if (ttlMillis <= 0) {
            return SendOutcome.EXPIRED;
        }
        try {
            SendPushRequest request = SendPushRequest.newBuilder()
                    .setUserId(driverId)
                    .setPayloadBytes(ByteString.copyFrom(offerJson, StandardCharsets.UTF_8))
                    .setTtlMs(ttlMillis)
                    .setPriority(priority)
                    .build();

            return sendTimer.record(() -> {
                SendPushResponse response = blockingStub
                        .withDeadlineAfter(Math.min(deadlineMs, ttlMillis), TimeUnit.MILLISECONDS)
                        .sendPush(request);

                if (response.getSuccess()) {
                    log.debug("[OfferPushClient] Offer sent to PushGateway: driverId={}", driverId);
                    return SendOutcome.ACCEPTED;
                } else {
                    meterRegistry.counter(
                            "cell_offer_push_send_rejections_total",
                            "reason", "gateway_rejected").increment();
                    log.warn("[OfferPushClient] PushGateway rejected offer for driverId={}: {}",
                            driverId, response.getReason());
                    return SendOutcome.REJECTED;
                }
            });
        } catch (StatusRuntimeException e) {
            meterRegistry.counter("cell_offer_push_send_failures_total",
                    "error", Status.fromThrowable(e).getCode().name()).increment();
            // The server may have accepted a request before the transport failed.
            return SendOutcome.AMBIGUOUS_FAILURE;
        } catch (Exception e) {
            meterRegistry.counter(
                    "cell_offer_push_send_failures_total",
                    "error", e.getClass().getSimpleName()).increment();
            log.error("[OfferPushClient] Failed to send offer to PushGateway for driverId={}: {}",
                    driverId, e.getMessage());
            return SendOutcome.INVALID;
        }
    }
}
