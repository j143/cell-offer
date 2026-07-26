package j143.github.celloffer.concurrencydemo;

public record CustomerSummaryResponse(String userId,
                                      CustomerProfile profile,
                                      OrderSnapshot orders,
                                      long elapsedMillis) {
}