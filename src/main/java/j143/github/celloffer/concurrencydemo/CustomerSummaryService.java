package j143.github.celloffer.concurrencydemo;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.StructuredTaskScope;

@Service
public class CustomerSummaryService {

    private final CustomerProfileClient profileClient;
    private final OrderSnapshotClient orderSnapshotClient;

    public CustomerSummaryService(CustomerProfileClient profileClient,
                                  OrderSnapshotClient orderSnapshotClient) {
        this.profileClient = profileClient;
        this.orderSnapshotClient = orderSnapshotClient;
    }

    public CustomerSummaryResponse fetchSummary(String userId) {
        Instant start = Instant.now();

        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var profileTask = scope.fork(() -> profileClient.fetchProfile(userId));
            var orderTask = scope.fork(() -> orderSnapshotClient.fetchOrderSnapshot(userId));

            scope.join();
            scope.throwIfFailed();

            return new CustomerSummaryResponse(
                    userId,
                    profileTask.get(),
                    orderTask.get(),
                    Duration.between(start, Instant.now()).toMillis()
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while building customer summary", e);
        }
    }
}