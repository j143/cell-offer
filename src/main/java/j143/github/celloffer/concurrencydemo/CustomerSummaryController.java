package j143.github.celloffer.concurrencydemo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/demo/users")
public class CustomerSummaryController {

    private final CustomerSummaryService customerSummaryService;

    public CustomerSummaryController(CustomerSummaryService customerSummaryService) {
        this.customerSummaryService = customerSummaryService;
    }

    @GetMapping("/{userId}/summary")
    public CustomerSummaryResponse getSummary(@PathVariable String userId) {
        return customerSummaryService.fetchSummary(userId);
    }
}