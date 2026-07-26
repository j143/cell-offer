package j143.github.celloffer.concurrencydemo;

import org.springframework.stereotype.Component;

@Component
public class SimulatedCustomerProfileClient implements CustomerProfileClient {

    @Override
    public CustomerProfile fetchProfile(String userId) {
        simulateBlockingIo(180);
        return new CustomerProfile(userId, "Customer " + userId, "ap-south-1", "gold");
    }

    private void simulateBlockingIo(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading customer profile", e);
        }
    }
}