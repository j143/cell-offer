package j143.github.celloffer.concurrencydemo;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerSummaryServiceTest {

    @Test
    void fetchSummaryRunsBlockingCallsConcurrently() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicBoolean profileCalled = new AtomicBoolean();
        AtomicBoolean ordersCalled = new AtomicBoolean();

        CustomerProfileClient profileClient = userId -> {
            profileCalled.set(true);
            awaitBarrier(barrier);
            return new CustomerProfile(userId, "Profile " + userId, "eu-west-1", "silver");
        };

        OrderSnapshotClient orderSnapshotClient = userId -> {
            ordersCalled.set(true);
            awaitBarrier(barrier);
            return new OrderSnapshot(userId, 1, 7, "shipped");
        };

        CustomerSummaryService service = new CustomerSummaryService(profileClient, orderSnapshotClient);

        CustomerSummaryResponse response = service.fetchSummary("u-42");

        assertThat(profileCalled).isTrue();
        assertThat(ordersCalled).isTrue();
        assertThat(response.userId()).isEqualTo("u-42");
        assertThat(response.profile().displayName()).isEqualTo("Profile u-42");
        assertThat(response.orders().completedOrders()).isEqualTo(7);
        assertThat(response.elapsedMillis()).isLessThan(1000);
    }

    private void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Expected both tasks to run in parallel", e);
        }
    }
}