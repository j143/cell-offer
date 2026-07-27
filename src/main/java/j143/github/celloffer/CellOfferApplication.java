package j143.github.celloffer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "j143.github")
@EnableScheduling
public class CellOfferApplication {

    public static void main(String[] args) {
        SpringApplication.run(CellOfferApplication.class, args);
    }
}
