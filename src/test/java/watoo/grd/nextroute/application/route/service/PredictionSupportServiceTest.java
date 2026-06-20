package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.port.out.MlSupportedRoutesPort;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PredictionSupportServiceTest {

    @Mock MlSupportedRoutesPort port;
    @InjectMocks PredictionSupportService service;

    @Test
    @DisplayName("초기 상태: 캐시 비어 모든 배지 false")
    void emptyBeforeRefresh() {
        assertThat(service.isSupported("R1")).isFalse();
        assertThat(service.supportedRouteIds()).isEmpty();
    }

    @Test
    @DisplayName("refresh 성공 시 지원 노선 캐싱")
    void refreshCachesSupportedRoutes() {
        given(port.fetchSupportedRouteIds()).willReturn(Optional.of(Set.of("R1", "R2")));

        service.refresh();

        assertThat(service.isSupported("R1")).isTrue();
        assertThat(service.isSupported("R3")).isFalse();
        assertThat(service.supportedRouteIds()).containsExactlyInAnyOrder("R1", "R2");
    }

    @Test
    @DisplayName("refresh 실패(empty)면 직전 캐시 유지")
    void refreshKeepsCacheOnFailure() {
        given(port.fetchSupportedRouteIds())
                .willReturn(Optional.of(Set.of("R1")))
                .willReturn(Optional.empty());

        service.refresh();
        service.refresh();

        assertThat(service.isSupported("R1")).isTrue();
    }

    @Test
    @DisplayName("null routeId는 false")
    void nullRouteId() {
        assertThat(service.isSupported(null)).isFalse();
    }
}
