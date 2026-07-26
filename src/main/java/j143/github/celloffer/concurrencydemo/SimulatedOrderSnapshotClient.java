package j143.github.celloffer.concurrencydemo;

import org.springframework.stereotype.Component;

@Component
public class SimulatedOrderSnapshotClient implements OrderSnapshotClient {

    @Override
    public OrderSnapshot fetchOrderSnapshot(String userId) {
        simulateBlockingIo(220);
        return new OrderSnapshot(userId, 2, 18, "delivered");
    }

    private void simulateBlockingIo(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading order snapshot", e);
        }
    }
}