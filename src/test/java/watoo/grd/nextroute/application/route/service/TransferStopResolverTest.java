package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 노선 해석 계약: 노선 번호 폴백은 원천 id 가 없거나 서울 노선 id 모양일 때만.
 * 다른 지역 노선이 번호만 같은 서울 노선으로 확정되면 안 된다(2026-10 충주 103번 → 서울 103번 실측).
 */
class TransferStopResolverTest {

    private final BusStopRepository stopRepo = mock(BusStopRepository.class);
    private final BusRouteRepository routeRepo = mock(BusRouteRepository.class);
    private final BusRouteStopRepository routeStopRepo = mock(BusRouteStopRepository.class);
    private final TransferStopResolver resolver = new TransferStopResolver(stopRepo, routeRepo, routeStopRepo);

    private static BusRoute seoulRoute(String id, String name) {
        return BusRoute.builder().routeId(id).routeName(name).build();
    }

    @Test
    @DisplayName("서울 노선 id 가 DB 에 있으면 그대로 확정(폴백 아님)")
    void seoulIdFound() {
        when(routeRepo.findByRouteId("100100038")).thenReturn(Optional.of(seoulRoute("100100038", "201")));

        assertThat(resolver.resolveRoute("100100038", "201"))
                .hasValueSatisfying(r -> {
                    assertThat(r.routeId()).isEqualTo("100100038");
                    assertThat(r.fallback()).isFalse();
                });
    }

    @Test
    @DisplayName("서울 노선 id 모양인데 DB 에 없으면 번호로 폴백한다(서울→서울)")
    void seoulShapedIdFallsBackByNumber() {
        when(routeRepo.findByRouteId("100199999")).thenReturn(Optional.empty());
        when(routeRepo.findByRouteNameIn(List.of("201"))).thenReturn(List.of(seoulRoute("100100038", "201")));

        assertThat(resolver.resolveRoute("100199999", "201"))
                .hasValueSatisfying(r -> assertThat(r.fallback()).isTrue());
    }

    @Test
    @DisplayName("원천 id 가 없으면 번호로 폴백한다(기존 동작)")
    void missingIdFallsBackByNumber() {
        when(routeRepo.findByRouteNameIn(List.of("201"))).thenReturn(List.of(seoulRoute("100100038", "201")));

        assertThat(resolver.resolveRoute(null, "201")).isPresent();
        assertThat(resolver.resolveRoute(" ", "201")).isPresent();
    }

    @Test
    @DisplayName("모양을 모르는 id(ODsay 내부 busID·임의 문자열)는 예전처럼 번호로 폴백한다")
    void unknownShapedIdStillFallsBack() {
        when(routeRepo.findByRouteId(anyString())).thenReturn(Optional.empty());
        when(routeRepo.findByRouteNameIn(List.of("370"))).thenReturn(List.of(seoulRoute("100100370", "370")));

        assertThat(resolver.resolveRoute("1052", "370")).isPresent();     // ODsay busID
        assertThat(resolver.resolveRoute("BL-370", "370")).isPresent();   // 시험 fixture 모양
    }

    @Test
    @DisplayName("경기 노선 id(234001577 M4130)는 번호 폴백 없이 empty")
    void gyeonggiIdDoesNotFallBack() {
        when(routeRepo.findByRouteId("234001577")).thenReturn(Optional.empty());

        assertThat(resolver.resolveRoute("234001577", "M4130")).isEmpty();
        verify(routeRepo, never()).findByRouteNameIn(any());
    }

    @Test
    @DisplayName("지방 TAGO 노선 id(충주 CHB…)는 번호가 같은 서울 노선(103)으로 둔갑하지 않는다")
    void tagoIdDoesNotBecomeSeoulRoute() {
        when(routeRepo.findByRouteId(anyString())).thenReturn(Optional.empty());
        when(routeRepo.findByRouteNameIn(List.of("103"))).thenReturn(List.of(seoulRoute("100100008", "103")));

        assertThat(resolver.resolveRoute("CHB272000090", "103")).isEmpty();
        verify(routeRepo, never()).findByRouteNameIn(any());
    }
}
