package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.journey.service.AdminJourneyNotificationTestService;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;

import java.time.Instant;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminNotificationTestController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminNotificationTestControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean AdminJourneyNotificationTestService testService;

    @Test
    void 테스트_알림을_enqueue하고_202를_반환한다() throws Exception {
        given(testService.enqueue(42L, JourneyCheckpoint.TRANSFER_TARGET_APPROACHING))
                .willReturn(new AdminJourneyNotificationTestService.QueuedTestNotification(
                        99L, 42L, JourneyCheckpoint.TRANSFER_TARGET_APPROACHING,
                        Instant.parse("2026-08-25T00:00:00Z"),
                        Instant.parse("2026-08-25T00:10:00Z")));

        mockMvc.perform(post("/api/admin/notifications/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"journeyId":42,"checkpoint":"TRANSFER_TARGET_APPROACHING"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.notificationId").value(99))
                .andExpect(jsonPath("$.journeyId").value(42))
                .andExpect(jsonPath("$.checkpoint").value("TRANSFER_TARGET_APPROACHING"));
    }

    @Test
    void 잘못된_요청은_400이다() throws Exception {
        mockMvc.perform(post("/api/admin/notifications/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"journeyId\":0,\"checkpoint\":\"UNKNOWN\"}"))
                .andExpect(status().isBadRequest());
    }
}
