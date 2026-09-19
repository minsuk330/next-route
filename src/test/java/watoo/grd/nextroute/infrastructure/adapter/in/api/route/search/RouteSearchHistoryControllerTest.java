package watoo.grd.nextroute.infrastructure.adapter.in.api.route.search;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.route.dto.RecentPlaceResponse;
import watoo.grd.nextroute.application.route.port.in.SearchHistoryUseCase;
import watoo.grd.nextroute.application.auth.config.AdminProperties;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RouteSearchHistoryController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class RouteSearchHistoryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean SearchHistoryUseCase searchHistoryUseCase;
    @MockBean JwtProvider jwtProvider;       // SecurityConfig 의존
    @MockBean TokenGenerationPort tokenGenerationPort; // SecurityConfig(JwtAuthFilter) 의존
    @MockBean AdminProperties adminProperties; // SecurityConfig(AdminApiKeyFilter) 의존

    private static UsernamePasswordAuthenticationToken authUser(long userId) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void getAll_returns200WithList() throws Exception {
        given(searchHistoryUseCase.getAll(7L)).willReturn(List.of(
                new RecentPlaceResponse(1L, 37.5, 127.0, "카페",
                        LocalDateTime.parse("2026-07-21T10:00:00"))));

        mockMvc.perform(get("/api/route/my/searches").with(authentication(authUser(7L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].lat").value(37.5))
                .andExpect(jsonPath("$[0].lng").value(127.0))
                .andExpect(jsonPath("$[0].name").value("카페"))
                .andExpect(jsonPath("$[0].searchedAt").value("2026-07-21T10:00:00"));
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/route/my/searches/1").with(authentication(authUser(7L))))
                .andExpect(status().isNoContent());

        verify(searchHistoryUseCase).delete(7L, 1L);
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        willThrow(new NoSuchElementException()).given(searchHistoryUseCase).delete(eq(7L), eq(99L));

        mockMvc.perform(delete("/api/route/my/searches/99").with(authentication(authUser(7L))))
                .andExpect(status().isNotFound());
    }

    @Test
    void clearAll_returns204() throws Exception {
        mockMvc.perform(delete("/api/route/my/searches").with(authentication(authUser(7L))))
                .andExpect(status().isNoContent());

        verify(searchHistoryUseCase).clear(7L);
    }

    @Test
    void withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/route/my/searches"))
                .andExpect(status().isUnauthorized());
    }
}
