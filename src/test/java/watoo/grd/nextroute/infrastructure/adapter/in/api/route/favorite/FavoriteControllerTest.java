package watoo.grd.nextroute.infrastructure.adapter.in.api.route.favorite;

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
import watoo.grd.nextroute.application.route.dto.FavoriteResponse;
import watoo.grd.nextroute.application.route.port.in.AddFavoriteRouteUseCase;
import watoo.grd.nextroute.application.route.port.in.DeleteFavoriteRouteUseCase;
import watoo.grd.nextroute.application.route.port.in.GetFavoriteRoutesUseCase;
import watoo.grd.nextroute.common.config.CorsConfig;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.common.security.SecurityConfig;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteType;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FavoriteController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class FavoriteControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean AddFavoriteRouteUseCase addFavoriteRouteUseCase;
    @MockBean GetFavoriteRoutesUseCase getFavoriteRoutesUseCase;
    @MockBean DeleteFavoriteRouteUseCase deleteFavoriteRouteUseCase;
    @MockBean JwtProvider jwtProvider; // SecurityConfig 의존

    private static UsernamePasswordAuthenticationToken authUser(long userId) {
        return new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void add_returns200WithResponse() throws Exception {
        FavoriteResponse response = FavoriteResponse.builder()
                .id(1L).type(FavoriteType.HOME).endPlace("집")
                .ex(127.1).ey(37.5).build();
        given(addFavoriteRouteUseCase.add(eq(7L), any())).willReturn(response);

        String body = """
                {"type":"HOME","endPlace":"집","ex":127.1,"ey":37.5}
                """;

        mockMvc.perform(post("/api/route/fav")
                        .with(authentication(authUser(7L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.endPlace").value("집"));
    }

    @Test
    void getAll_returns200WithList() throws Exception {
        given(getFavoriteRoutesUseCase.getAll(7L)).willReturn(List.of(
                FavoriteResponse.builder().id(1L).type(FavoriteType.HOME).endPlace("집")
                        .ex(127.1).ey(37.5).build()
        ));

        mockMvc.perform(get("/api/route/fav").with(authentication(authUser(7L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("HOME"));
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/route/fav/1").with(authentication(authUser(7L))))
                .andExpect(status().isNoContent());

        verify(deleteFavoriteRouteUseCase).delete(7L, 1L);
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        willThrow(new NoSuchElementException()).given(deleteFavoriteRouteUseCase).delete(eq(7L), eq(99L));

        mockMvc.perform(delete("/api/route/fav/99").with(authentication(authUser(7L))))
                .andExpect(status().isNotFound());
    }

    @Test
    void withoutAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/route/fav"))
                .andExpect(status().isUnauthorized());
    }
}
