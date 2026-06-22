package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.route.port.out.WalkSegmentCachePort;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WalkCacheAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class WalkCacheAdminControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean WalkSegmentCachePort cache;

    @Test
    void TC_prefix_없으면_invalidateAll_호출() throws Exception {
        given(cache.invalidateAll()).willReturn(42);

        mockMvc.perform(delete("/api/admin/walk-cache"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(42))
                .andExpect(jsonPath("$.prefix").doesNotExist());

        verify(cache).invalidateAll();
        verify(cache, never()).invalidateByPrefix(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void TC_prefix_있으면_invalidateByPrefix_호출() throws Exception {
        given(cache.invalidateByPrefix("coord:127.")).willReturn(7);

        mockMvc.perform(delete("/api/admin/walk-cache").param("prefix", "coord:127."))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(7))
                .andExpect(jsonPath("$.prefix").value("coord:127."));

        verify(cache).invalidateByPrefix(eq("coord:127."));
        verify(cache, never()).invalidateAll();
    }

    @Test
    void TC_prefix_빈문자열은_invalidateAll로_위임() throws Exception {
        given(cache.invalidateAll()).willReturn(5);

        mockMvc.perform(delete("/api/admin/walk-cache").param("prefix", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(5));

        verify(cache).invalidateAll();
        verify(cache, never()).invalidateByPrefix(org.mockito.ArgumentMatchers.any());
    }
}
