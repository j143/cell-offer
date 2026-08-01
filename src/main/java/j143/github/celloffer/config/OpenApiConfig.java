package j143.github.celloffer.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.Contact;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    /** Global metadata — version comes from your Maven POM via @Value or hardcoded */
    @Bean
    public OpenAPI cellOfferOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("CellOffer Dispatch API")
                .description("Bounded per-cell priority queue with TTL and push metrics")
                .version("v1")
                .contact(new Contact().name("j143").url("https://github.com/j143/cell-offer")));
    }

    /** v1 group — all /cells endpoints */
    @Bean
    public GroupedOpenApi v1Api() {
        return GroupedOpenApi.builder()
            .group("v1")
            .pathsToMatch("/cells/**")
            .build();
    }

    /** v2 group — future breaking changes go here */
    @Bean
    public GroupedOpenApi v2Api() {
        return GroupedOpenApi.builder()
            .group("v2")
            .pathsToMatch("/v2/cells/**")
            .build();
    }
}