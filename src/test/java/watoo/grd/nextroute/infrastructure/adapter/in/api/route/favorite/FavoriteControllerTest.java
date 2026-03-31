package watoo.grd.nextroute.infrastructure.adapter.in.api.route.favorite;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.route.dto.FavoriteResponse;
import watoo.grd.nextroute.application.route.port.in.AddFavoriteRouteUseCase;
import watoo.grd.nextroute.application.route.port.in.DeleteFavoriteRouteUseCase;
import watoo.grd.nextroute.application.route.port.in.GetFavoriteRoutesUseCase;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteType;

import java.util.List;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FavoriteController.class)
class FavoriteControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean AddFavoriteRouteUseCase addFavoriteRouteUseCase;
    @MockBean GetFavoriteRoutesUseCase getFavoriteRoutesUseCase;
    @MockBean DeleteFavoriteRouteUseCase deleteFavoriteRouteUseCase;

    @Test
    void add_returns200WithResponse() throws Exception {
        FavoriteResponse response = FavoriteResponse.builder()
                .id(1L).type(FavoriteType.HOME).endPlace("집")
                .ex(127.1).ey(37.5).build();
        given(addFavoriteRouteUseCase.add(eq("device-1"), any())).willReturn(response);

        String body = """
                {"type":"HOME","endPlace":"집","ex":127.1,"ey":37.5}
                """;

        mockMvc.perform(post("/api/route/fav")
                        .header("X-Device-Id", "device-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.endPlace").value("집"));
    }

    @Test
    void getAll_returns200WithList() throws Exception {
        given(getFavoriteRoutesUseCase.getAll("device-1")).willReturn(List.of(
                FavoriteResponse.builder().id(1L).type(FavoriteType.HOME).endPlace("집")
                        .ex(127.1).ey(37.5).build()
        ));

        mockMvc.perform(get("/api/route/fav").header("X-Device-Id", "device-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("HOME"));
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/route/fav/1").header("X-Device-Id", "device-1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        willThrow(new NoSuchElementException()).given(deleteFavoriteRouteUseCase).delete(any(), eq(99L));

        mockMvc.perform(delete("/api/route/fav/99").header("X-Device-Id", "device-1"))
                .andExpect(status().isNotFound());
    }
}
