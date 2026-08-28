package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.route.dto.RecentPlaceResponse;
import watoo.grd.nextroute.application.route.port.out.SearchHistoryManagementPort;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchHistory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteSearchHistoryServiceTest {

    @Mock SearchHistoryManagementPort managementPort;
    @InjectMocks RouteSearchHistoryService service;

    /** 검색행 한 건. lat=Y, lng=X 매핑을 검증하려 출/도착 좌표를 다르게 둔다. */
    private RouteSearchHistory row(long id,
                                  double startX, double startY, String startName,
                                  double endX, double endY, String endName,
                                  LocalDateTime at) {
        RouteSearchHistory h = RouteSearchHistory.builder()
                .startX(startX).startY(startY).endX(endX).endY(endY)
                .startName(startName).endName(endName).pathCount(2).searchType(0)
                .searchedAt(at)
                .build();
        ReflectionTestUtils.setField(h, "id", id);  // id 는 BaseEntity 라 builder 로 못 넣음
        return h;
    }

    @Test
    void TC_getAll_한행을_출발_도착_두_장소로_평탄화하고_좌표를_lat_Y_lng_X로_매핑() {
        given(managementPort.findByUser(7L)).willReturn(List.of(
                row(1L, 127.0, 37.5, "출발", 127.01, 37.51, "도착",
                        LocalDateTime.parse("2026-07-21T10:00:00"))));

        List<RecentPlaceResponse> result = service.getAll(7L);

        // 행 내 출발 먼저 → 도착. lat=Y, lng=X.
        assertThat(result).extracting(RecentPlaceResponse::name).containsExactly("출발", "도착");
        assertThat(result.get(0).lat()).isEqualTo(37.5);
        assertThat(result.get(0).lng()).isEqualTo(127.0);
        assertThat(result.get(1).lat()).isEqualTo(37.51);
        assertThat(result.get(1).lng()).isEqualTo(127.01);
        assertThat(result).allSatisfy(p -> assertThat(p.id()).isEqualTo(1L));
    }

    @Test
    void TC_getAll_같은_좌표는_dedup되고_대표는_최근_등장_행() {
        // 집→카페(t1), 카페→회사(t2>t1). "카페"(127.5,37.9) 좌표가 두 행에 등장.
        // 포트는 최신순(searched_at DESC)으로 돌려주므로 t2 행이 먼저 온다.
        given(managementPort.findByUser(7L)).willReturn(List.of(
                row(2L, 127.5, 37.9, "카페", 127.9, 37.1, "회사",
                        LocalDateTime.parse("2026-07-21T12:00:00")),   // t2
                row(1L, 127.0, 37.5, "집", 127.5, 37.9, "카페",
                        LocalDateTime.parse("2026-07-21T10:00:00"))));  // t1

        List<RecentPlaceResponse> result = service.getAll(7L);

        // 좌표: 카페(127.5,37.9), 회사(127.9,37.1), 집(127.0,37.5) — 3개로 dedup
        assertThat(result).extracting(RecentPlaceResponse::name)
                .containsExactly("카페", "회사", "집");
        RecentPlaceResponse cafe = result.get(0);
        assertThat(cafe.lat()).isEqualTo(37.9);
        assertThat(cafe.lng()).isEqualTo(127.5);
        // 대표 = t2 행(카페가 출발로 등장). id·searchedAt·이름 모두 t2 행 기준.
        assertThat(cafe.id()).isEqualTo(2L);
        assertThat(cafe.searchedAt()).isEqualTo(LocalDateTime.parse("2026-07-21T12:00:00"));
    }

    @Test
    void TC_getAll_같은_searchedAt이면_큰_id행이_대표() {
        // 포트 정렬 tie-break 는 (searched_at DESC, id DESC) → 큰 id 가 먼저 온다.
        LocalDateTime same = LocalDateTime.parse("2026-07-21T10:00:00");
        given(managementPort.findByUser(7L)).willReturn(List.of(
                row(9L, 127.5, 37.9, "카페-최신라벨", 128.0, 38.0, "A", same),
                row(3L, 127.5, 37.9, "카페-옛라벨", 128.1, 38.1, "B", same)));

        List<RecentPlaceResponse> result = service.getAll(7L);

        RecentPlaceResponse cafe = result.stream()
                .filter(p -> p.lng().equals(127.5) && p.lat().equals(37.9))
                .findFirst().orElseThrow();
        assertThat(cafe.id()).isEqualTo(9L);
        assertThat(cafe.name()).isEqualTo("카페-최신라벨");
    }

    @Test
    void TC_getAll_이력없으면_빈리스트() {
        given(managementPort.findByUser(7L)).willReturn(List.of());

        assertThat(service.getAll(7L)).isEmpty();
    }

    @Test
    void TC_delete_본인소유행_삭제() {
        when(managementPort.deleteByIdAndUser(3L, 7L)).thenReturn(1L);

        service.delete(7L, 3L);

        verify(managementPort).deleteByIdAndUser(3L, 7L);
    }

    @Test
    void TC_delete_타인소유거나_부재면_NoSuchElement() {
        when(managementPort.deleteByIdAndUser(99L, 7L)).thenReturn(0L);

        assertThatThrownBy(() -> service.delete(7L, 99L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void TC_clear_전체삭제_위임() {
        service.clear(7L);

        verify(managementPort).deleteByUser(7L);
    }
}
