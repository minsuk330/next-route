package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.application.route.port.out.WalkSegmentCachePort;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/admin/** 는 유효한 X-Admin-Key 없이는 401(fail-closed), 있으면 통과. 필터 체인 적용(addFilters 기본 true). */
@WebMvcTest(WalkCacheAdminController.class)
@Import({SecurityConfig.class, CorsConfig.class})
@EnableConfigurationProperties(AdminProperties.class)
@TestPropertySource(properties = "auth.admin.api-key=super-secret")
class AdminApiKeySecurityTest {

    @Autowired MockMvc mockMvc;

    @MockBean WalkSegmentCachePort cache;
    @MockBean JwtProvider jwtProvider;

    @Test
    void admin_withoutKey_returns401() throws Exception {
        mockMvc.perform(delete("/api/admin/walk-cache"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_withWrongKey_returns401() throws Exception {
        mockMvc.perform(delete("/api/admin/walk-cache").header("X-Admin-Key", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void admin_withValidKey_isPermitted() throws Exception {
        given(cache.invalidateAll()).willReturn(0);

        mockMvc.perform(delete("/api/admin/walk-cache").header("X-Admin-Key", "super-secret"))
                .andExpect(status().isOk());
    }
}
