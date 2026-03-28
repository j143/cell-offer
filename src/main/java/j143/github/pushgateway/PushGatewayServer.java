package j143.github.pushgateway;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Lifecycle bean that starts and stops the gRPC {@code PushGateway} server.
 *
 * <p>The server binds on {@code push.gateway.grpc-port} (default 9090) and
 * registers {@link PushServiceImpl} as the only service. It is started after
 * the Spring context is fully initialised and shut down gracefully when the
 * application stops.
 */
@Component
public class PushGatewayServer {

    private static final Logger log = LoggerFactory.getLogger(PushGatewayServer.class);

    private final PushServiceImpl pushService;
    private final int grpcPort;

    private Server server;

    public PushGatewayServer(
            PushServiceImpl pushService,
            @Value("${push.gateway.grpc-port:9090}") int grpcPort) {
        this.pushService = pushService;
        this.grpcPort    = grpcPort;
    }

    @PostConstruct
    public void start() throws IOException {
        server = NettyServerBuilder.forPort(grpcPort)
                .addService(pushService)
                .build()
                .start();
        log.info("[PushGatewayServer] gRPC server started on port {}", grpcPort);
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            log.info("[PushGatewayServer] Shutting down gRPC server...");
            server.shutdown();
            try {
                if (!server.awaitTermination(5, TimeUnit.SECONDS)) {
                    server.shutdownNow();
                }
            } catch (InterruptedException e) {
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[PushGatewayServer] gRPC server stopped.");
        }
    }
}
