package j143.github.celloffer.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enables aggregable latency histograms for load-diagnosis timers and HTTP requests.
 * Existing component Javadoc and delivery behavior are intentionally unchanged.
 */
@Configuration(proxyBeanMethods = false)
public class LoadDiagnosisMetricsConfiguration {

    /**
     * Publishes buckets needed by Grafana histogram_quantile queries.
     *
     * @return filter restricted to the three diagnostic timers and the HTTP timer
     */
    @Bean
    public MeterFilter loadDiagnosisHistogramFilter() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(
                    Meter.Id id, DistributionStatisticConfig config) {
                String name = id.getName();
                if (name.equals("cell_offer_enqueue_duration")
                        || name.equals("cell_offer_flush_cycle_duration")
                        || name.equals("cell_offer_push_send_duration")
                        || name.equals("http.server.requests")) {
                    return DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .build()
                            .merge(config);
                }
                return config;
            }
        };
    }
}
