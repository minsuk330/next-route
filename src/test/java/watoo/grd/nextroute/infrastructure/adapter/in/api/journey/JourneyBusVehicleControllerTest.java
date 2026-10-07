package watoo.grd.nextroute.infrastructure.adapter.in.api.journey;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.application.journey.service.TrackBusVehicleResetService;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.GlobalExceptionHandler;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.util.List;

import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JourneyBusVehicleController.class)
@Import({SecurityConfig.class, CorsConfig.class, GlobalExceptionHandler.class, JourneyControllerAdvice.class})
@DisplayName("버스 차량 해제 API")
class JourneyBusVehicleControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean TrackBusVehicleResetService resetService;
    @MockBean JwtProvider jwtProvider;
    @MockBean TokenGenerationPort tokenGenerationPort;
    @MockBean AdminProperties adminProperties;

    private final UsernamePasswordAuthenticationToken user = new UsernamePasswordAuthenticationToken(
            1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

    @Test
    @DisplayName("401 / 204 / 409 NOT_RIDING_BUS")
    void contract() throws Exception {
        mockMvc.perform(post("/api/journeys/7/bus/vehicle/reset")).andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/journeys/7/bus/vehicle/reset").with(authentication(user)))
                .andExpect(status().isNoContent());
        verify(resetService).reset(1L, 7L);

        willThrow(new BusinessException(JourneyErrorCode.NOT_RIDING_BUS)).given(resetService).reset(1L, 8L);
        mockMvc.perform(post("/api/journeys/8/bus/vehicle/reset").with(authentication(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_RIDING_BUS"));
    }
}
