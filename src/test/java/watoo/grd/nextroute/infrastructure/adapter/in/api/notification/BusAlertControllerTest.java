package watoo.grd.nextroute.infrastructure.adapter.in.api.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.application.notification.dto.BusAlertResponse;
import watoo.grd.nextroute.application.notification.port.in.BusAlertUseCase;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.notification.entity.AlertStatus;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BusAlertController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class BusAlertControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean BusAlertUseCase busAlertUseCase;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort; // SecurityConfig(JwtAuthFilter) 의존
    @MockBean AdminProperties adminProperties;

    private static UsernamePasswordAuthenticationToken authUser(long userId) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void create_returns200Pending() throws Exception {
        given(busAlertUseCase.create(eq(7L), any())).willReturn(
                BusAlertResponse.builder().id(1L).status(AlertStatus.PENDING)
                        .stopId("S1").routeId("R1").routeName("간선143").stopName("강남역").build());

        String body = """
                {"stopId":"S1","routeId":"R1","userEta":"2026-06-23T10:00:00","busArrivalMinutes":8}
                """;

        mockMvc.perform(post("/api/notifications/bus-alert")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.routeName").value("간선143"));
    }

    @Test
    void create_missingRouteId_returns400() throws Exception {
        String body = """
                {"stopId":"S1"}
                """;

        mockMvc.perform(post("/api/notifications/bus-alert")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/notifications/bus-alert"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cancel_returns204() throws Exception {
        mockMvc.perform(delete("/api/notifications/bus-alert/1").with(authentication(authUser(7L))))
                .andExpect(status().isNoContent());

        verify(busAlertUseCase).cancel(7L, 1L);
    }
}
