package j143.github.celloffer.concurrencydemo;

public record OrderSnapshot(String userId, int openOrders, int completedOrders, String lastOrderState) {
}