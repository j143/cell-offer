package j143.github.celloffer.push;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import j143.github.push.proto.PushServiceGrpc;
import j143.github.push.proto.SendPushRequest;
import j143.github.push.proto.SendPushResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
public class OfferPushClient {

    private static final Logger log = LoggerFactory.getLogger(OfferPushClient.class);

    private final String host;
    private final int port;
    private final long deadlineMs;
    private final MeterRegistry meterRegistry;
    private final Timer sendTimer;

    private ManagedChannel channel;
    private PushServiceGrpc.PushServiceBlockingStub blockingStub;

    public OfferPushClient(
            @Value("${push.gateway.host:localhost}") String host,
            @Value("${push.gateway.grpc-port:9090}") int port,
            @Value("${push.gateway.send-deadline-ms:250}") long deadlineMs,
            MeterRegistry meterRegistry) {
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

    public void sendOffer(String driverId, String offerJson, long ttlMillis, int priority) {
        SendPushRequest request = SendPushRequest.newBuilder()
                .setUserId(driverId)
                .setPayloadBytes(ByteString.copyFrom(offerJson, StandardCharsets.UTF_8))
                .setTtlMs(ttlMillis)
                .setPriority(priority)
                .build();

        try {
            sendTimer.record(() -> {
                SendPushResponse response = blockingStub
                        .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                        .sendPush(request);

                if (response.getSuccess()) {
                    log.debug("[OfferPushClient] Offer sent to PushGateway: driverId={}", driverId);
                } else {
                    meterRegistry.counter(
                            "cell_offer_push_send_rejections_total",
                            "reason", response.getReason()).increment();
                    log.warn("[OfferPushClient] PushGateway rejected offer for driverId={}: {}",
                            driverId, response.getReason());
                }
            });
        } catch (Exception e) {
            meterRegistry.counter(
                    "cell_offer_push_send_failures_total",
                    "error", e.getClass().getSimpleName()).increment();
            log.error("[OfferPushClient] Failed to send offer to PushGateway for driverId={}: {}",
                    driverId, e.getMessage());
        }
    }
}
