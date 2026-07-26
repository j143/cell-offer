package j143.github.celloffer.concurrencydemo;

public interface OrderSnapshotClient {

    OrderSnapshot fetchOrderSnapshot(String userId);
}