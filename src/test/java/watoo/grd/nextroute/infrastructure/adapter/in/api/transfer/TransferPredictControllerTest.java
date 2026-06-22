package watoo.grd.nextroute.infrastructure.adapter.in.api.transfer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.route.config.TransferPredictProperties;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferPredictController.class)
@AutoConfigureMockMvc(addFilters = false)
class TransferPredictControllerTest {

    static final Instant NOW = Instant.parse("2026-06-06T04:00:00Z");

    @Autowired MockMvc mockMvc;
    @MockBean PredictTransferUseCase predictTransferUseCase;

    @TestConfiguration
    static class Cfg {
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean TransferPredictProperties props() { return new TransferPredictProperties(); }
    }

    @Test
    void valid_returns200() throws Exception {
        Instant arr = NOW.plusSeconds(600);
        given(predictTransferUseCase.predict(eq("1001"), eq("R1"), eq(5), any()))
                .willReturn(TransferPredictionResult.available("1001", "R1", 5,
                        TransferArrival.Source.REALTIME, NOW, arr, arr.plusSeconds(120), "v1", null));

        mockMvc.perform(get("/api/transfer/predict")
                        .param("stopId", "1001").param("routeId", "R1")
                        .param("seq", "5").param("userArrivalAt", arr.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("REALTIME"))
                .andExpect(jsonPath("$.boardable").value(true));
    }

    @Test
    void blankStopId_400() throws Exception {
        mockMvc.perform(get("/api/transfer/predict")
                        .param("stopId", "").param("routeId", "R1")
                        .param("userArrivalAt", NOW.plusSeconds(600).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nonPositiveSeq_400() throws Exception {
        mockMvc.perform(get("/api/transfer/predict")
                        .param("stopId", "1001").param("routeId", "R1")
                        .param("seq", "0").param("userArrivalAt", NOW.plusSeconds(600).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void badIsoTime_400() throws Exception {
        mockMvc.perform(get("/api/transfer/predict")
                        .param("stopId", "1001").param("routeId", "R1")
                        .param("userArrivalAt", "not-a-time"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pastTime_400() throws Exception {
        mockMvc.perform(get("/api/transfer/predict")
                        .param("stopId", "1001").param("routeId", "R1")
                        .param("userArrivalAt", NOW.minusSeconds(120).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tooFarFuture_400() throws Exception {
        mockMvc.perform(get("/api/transfer/predict")
                        .param("stopId", "1001").param("routeId", "R1")
                        .param("userArrivalAt", NOW.plusSeconds(40 * 60 + 120).toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingUserArrivalAt_400() throws Exception {
        mockMvc.perform(get("/api/transfer/predict")
                        .param("stopId", "1001").param("routeId", "R1"))
                .andExpect(status().isBadRequest());
    }
}
