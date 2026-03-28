package j143.github.celloffer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import j143.github.celloffer.dto.EnqueueOfferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DispatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private EnqueueOfferRequest buildRequest(String driverId, String riderId,
                                              int priority, long ttlMillis) {
        EnqueueOfferRequest req = new EnqueueOfferRequest();
        req.setDriverId(driverId);
        req.setRiderId(riderId);
        req.setPriority(priority);
        req.setTtlMillis(ttlMillis);
        return req;
    }

    @Test
    void postOffer_returns202AndOfferId() throws Exception {
        EnqueueOfferRequest req = buildRequest("driver-1", "rider-1", 5, 30_000L);

        mockMvc.perform(post("/cells/cell-test-1/offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.offerId").isNotEmpty())
                .andExpect(jsonPath("$.cellId").value("cell-test-1"));
    }

    @Test
    void getStats_returnsExpectedFields() throws Exception {
        // Enqueue one offer first
        EnqueueOfferRequest req = buildRequest("driver-2", "rider-2", 3, 30_000L);
        mockMvc.perform(post("/cells/cell-stats-test/offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/cells/cell-stats-test/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cellId").value("cell-stats-test"))
                .andExpect(jsonPath("$.enqueued").value(1))
                .andExpect(jsonPath("$.droppedOnEnqueue").value(0));
    }

    @Test
    void getAllStats_returnsMap() throws Exception {
        // Enqueue an offer so the cell is registered
        EnqueueOfferRequest req = buildRequest("driver-3", "rider-3", 7, 30_000L);
        mockMvc.perform(post("/cells/cell-all-stats/offers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isAccepted());

        mockMvc.perform(get("/cells/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cell-all-stats").exists())
                .andExpect(jsonPath("$.cell-all-stats.enqueued").value(1));
    }
}
