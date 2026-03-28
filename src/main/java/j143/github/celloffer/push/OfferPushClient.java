package j143.github.celloffer.push;

import com.google.protobuf.ByteString;
import j143.github.push.proto.PushServiceGrpc;
import j143.github.push.proto.SendPushRequest;
import j143.github.push.proto.SendPushResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
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

    private ManagedChannel channel;
    private PushServiceGrpc.PushServiceBlockingStub blockingStub;

    public OfferPushClient(
            @Value("${push.gateway.host:localhost}") String host,
            @Value("${push.gateway.grpc-port:9090}") int port) {
        this.host = host;
        this.port = port;
    }

    @PostConstruct
    public void init() {
        channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        blockingStub = PushServiceGrpc.newBlockingStub(channel);
        log.info("[OfferPushClient] Initialized gRPC channel to {}:{}", host, port);
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
     * @param driverId   the target driver (userId in PushGateway terms)
     * @param offerJson  JSON representation of the offer
     * @param ttlMillis  message time-to-live in milliseconds
     * @param priority   offer priority (higher = more urgent)
     */
    public void sendOffer(String driverId, String offerJson, long ttlMillis, int priority) {
        try {
            SendPushRequest request = SendPushRequest.newBuilder()
                    .setUserId(driverId)
                    .setPayloadBytes(ByteString.copyFrom(offerJson, StandardCharsets.UTF_8))
                    .setTtlMs(ttlMillis)
                    .setPriority(priority)
                    .build();

            SendPushResponse response = blockingStub.sendPush(request);

            if (response.getSuccess()) {
                log.debug("[OfferPushClient] Offer sent to PushGateway: driverId={}", driverId);
            } else {
                log.warn("[OfferPushClient] PushGateway rejected offer for driverId={}: {}",
                        driverId, response.getReason());
            }
        } catch (Exception e) {
            log.error("[OfferPushClient] Failed to send offer to PushGateway for driverId={}: {}",
                    driverId, e.getMessage());
        }
    }
}
