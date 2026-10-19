package j143.github.celloffer.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import j143.github.celloffer.model.Offer;
import j143.github.celloffer.queue.CellOfferQueueManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DispatchContractTest {
    @Test
    void missingZeroNegativeAndWrongUnitTtlAreRejectedBeforeAdmission() throws Exception {
        CellOfferQueueManager queues = mock(CellOfferQueueManager.class);
        MockMvc mvc = mvc(queues);
        for (String json : new String[] {
                "{\"driverId\":\"d\"}",
                "{\"driverId\":\"d\",\"ttlMillis\":0}",
                "{\"driverId\":\"d\",\"ttlMillis\":-1}",
                "{\"driverId\":\"d\",\"ttlSeconds\":30}" }) {
            mvc.perform(post("/cells/a/offers").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("ttlMillis_must_be_positive"));
        }
        verifyNoInteractions(queues);
    }

    @Test
    void validTtlAndCapacityRejectionHaveDistinctStatuses() throws Exception {
        CellOfferQueueManager queues = mock(CellOfferQueueManager.class);
        when(queues.enqueue(any())).thenReturn(true, false);
        MockMvc mvc = mvc(queues);
        String json = "{\"driverId\":\"d\",\"ttlMillis\":30000}";
        mvc.perform(post("/cells/a/offers").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted());
        mvc.perform(post("/cells/a/offers").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void activeIndexExcludesHistoricalEmptyCellsAndReactivates() {
        try (SimpleMeterRegistry registry = new SimpleMeterRegistry()) {
            CellOfferQueueManager queues = new CellOfferQueueManager(100, registry);
            Instant now = Instant.now();
            for (int i = 0; i < 1000; i++) {
                String cell = "old-" + i;
                queues.enqueue(offer(cell, now));
                queues.pollForDispatch(cell, now);
            }
            assertThat(queues.getCellIds()).hasSize(1000);
            assertThat(queues.nextActiveCell(null)).isNull();
            assertThat(queues.getActiveCellCount()).isZero();
            queues.enqueue(offer("old-2", now));
            assertThat(queues.nextActiveCell(null)).isEqualTo("old-2");
            assertThat(queues.getActiveCellCount()).isEqualTo(1);
            queues.flushDueOffers("old-2", now);
            assertThat(queues.nextActiveCell(null)).isNull();
            assertThat(registry.get("cell_offer_queued").gauge().value()).isZero();
        }
    }

    @Test
    void expirationRemovesActiveMembershipAndAge() {
        try (SimpleMeterRegistry registry = new SimpleMeterRegistry()) {
            CellOfferQueueManager queues = new CellOfferQueueManager(100, registry);
            Instant past = Instant.now().minusSeconds(60);
            queues.enqueue(offer("expired", past));
            assertThat(queues.pollForDispatch("expired", Instant.now())).isNull();
            assertThat(queues.nextActiveCell(null)).isNull();
            assertThat(registry.get("cell_offer_oldest_queued_age_seconds").gauge().value()).isZero();
            assertThat(registry.get("cell_offer_queue_expired_total").counter().count()).isEqualTo(1);
        }
    }

    private MockMvc mvc(CellOfferQueueManager queues) {
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return MockMvcBuilders.standaloneSetup(new DispatchController(queues))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper)).build();
    }

    private Offer offer(String cell, Instant createdAt) {
        return Offer.builder().offerId(cell).cellId(cell).driverId("d").priority(1)
                .createdAt(createdAt).ttl(Duration.ofSeconds(30)).build();
    }
}
