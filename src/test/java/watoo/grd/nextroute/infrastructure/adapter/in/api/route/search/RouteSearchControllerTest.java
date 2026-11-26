package watoo.grd.nextroute.infrastructure.adapter.in.api.route.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.config.RouteSnapshotProperties;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SearchMeta;
import watoo.grd.nextroute.application.route.port.in.RecordSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.route.service.RouteSearchSnapshotRecorder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * optional auth 배선 검증: 익명(userId=null)이면 이력 기록을 호출하지 않고, 로그인 사용자면 기록한다.
 * 검색 자체는 어느 쪽이든 동일하게 반환된다.
 *
 * <p>스냅샷 부착도 여기서 일어난다 — 익명·로그인 모두 {@code searchSessionId} 를 받고,
 * 저장이 실패해도 기존 응답 필드와 200 은 그대로다.
 */
@ExtendWith(MockitoExtension.class)
class RouteSearchControllerTest {

    private static final Instant NOW = Instant.parse("2026-07-28T10:40:00Z");

    @Mock SearchRouteUseCase searchRouteUseCase;
    @Mock RecordSearchHistoryUseCase recordSearchHistoryUseCase;
    @Mock RouteSearchSnapshotPort snapshotPort;
    @Mock watoo.grd.nextroute.application.route.service.RouteSearchEventRecorder searchEventRecorder;

    private RouteSnapshotProperties snapshotProperties;
    private RouteSearchController controller;

    @BeforeEach
    void setUp() {
        snapshotProperties = new RouteSnapshotProperties();
        RouteSearchSnapshotRecorder recorder = new RouteSearchSnapshotRecorder(
                snapshotPort, snapshotProperties, Clock.fixed(NOW, ZoneOffset.UTC));
        controller = new RouteSearchController(
                searchRouteUseCase, recordSearchHistoryUseCase, recorder, eventRecorders(searchEventRecorder));
    }

    private RouteSearchRequest request() {
        return new RouteSearchRequest(127.0, 37.5, 127.01, 37.51, "출발지", "도착지");
    }

    private static org.springframework.beans.factory.ObjectProvider<watoo.grd.nextroute.application.route.service.RouteSearchEventRecorder>
            eventRecorders(watoo.grd.nextroute.application.route.service.RouteSearchEventRecorder recorder) {
        var factory = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        if (recorder != null) {
            factory.addBean("searchEventRecorder", recorder);
        }
        return factory.getBeanProvider(watoo.grd.nextroute.application.route.service.RouteSearchEventRecorder.class);
    }

    /** 경로가 없는 결과 — 스냅샷 저장 대상이 아니라 기존 배선만 보는 테스트에 쓴다. */
    private RouteSearchResult emptyResult() {
        return new RouteSearchResult(2, 0, 0, 0, 0, List.of());
    }

    private RouteSearchResult result() {
        return new RouteSearchResult(2, 0, 1, 0, 0, List.of(
                        new PathResult(1, info(), List.of(), List.of()),
                        new PathResult(2, info(), List.of(), List.of())))
                .withMeta(new SearchMeta("full", List.of()));
    }

    private static PathInfo info() {
        return new PathInfo(30, 1250, 300, 1, "강남", "판교", "mapObj");
    }

    // ── 이력 배선 ────────────────────────────────────────────────────────────

    @Test
    void TC_익명_검색은_이력을_기록하지_않는다() {
        RouteSearchRequest request = request();
        RouteSearchResult result = emptyResult();
        when(searchRouteUseCase.search(request)).thenReturn(result);

        var response = controller.search(null, request);

        assertThat(response.getBody()).isSameAs(result);
        verifyNoInteractions(recordSearchHistoryUseCase);
    }

    @Test
    void TC_로그인_검색은_이력을_기록한다() {
        RouteSearchRequest request = request();
        RouteSearchResult result = emptyResult();
        when(searchRouteUseCase.search(request)).thenReturn(result);

        var response = controller.search(42L, request);

        assertThat(response.getBody()).isSameAs(result);
        verify(recordSearchHistoryUseCase).record(42L, request, result);
    }

    // ── 스냅샷 부착 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("익명 검색도 searchSessionId 를 받는다 — ownerUserId 는 null 로 저장")
    void anonymousSearchGetsSessionId() {
        RouteSearchRequest request = request();
        when(searchRouteUseCase.search(request)).thenReturn(result());
        when(snapshotPort.save(any(), any(), anyBoolean(), any())).thenReturn("session-anon");

        RouteSearchResult body = controller.search(null, request).getBody();

        assertThat(body.searchSessionId()).isEqualTo("session-anon");
        assertThat(body.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(body.paths()).extracting(PathResult::pathKey).containsExactly("0", "1");
        verify(snapshotPort).save(isNull(), eq(body.withSnapshot(null, null)), eq(false), eq(NOW.plusSeconds(600)));
    }

    @Test
    @DisplayName("로그인 검색은 ownerUserId 와 함께 저장되고, 이력에는 스냅샷이 붙은 결과가 넘어간다")
    void authenticatedSearchGetsSessionId() {
        RouteSearchRequest request = request();
        when(searchRouteUseCase.search(request)).thenReturn(result());
        when(snapshotPort.save(any(), any(), anyBoolean(), any())).thenReturn("session-42");

        RouteSearchResult body = controller.search(42L, request).getBody();

        assertThat(body.searchSessionId()).isEqualTo("session-42");
        verify(snapshotPort).save(eq(42L), any(), anyBoolean(), any());
        verify(recordSearchHistoryUseCase).record(42L, request, body);
    }

    @Test
    @DisplayName("급행 끄기 검색은 스냅샷에 true 로 남는다 — D10 으로 필터가 풀린 결과여도 요청값 그대로(subway-express S5)")
    void excludeExpressKeptEvenWhenRelaxed() {
        RouteSearchRequest request = new RouteSearchRequest(127.0, 37.5, 127.01, 37.51, "출발지", "도착지",
                null, true);
        RouteSearchResult relaxed = result().withMeta(new SearchMeta("partial",
                List.of(new SearchMeta.DegradedComponent("express_filter", "empty_result"))));
        when(searchRouteUseCase.search(request)).thenReturn(relaxed);
        when(snapshotPort.save(any(), any(), anyBoolean(), any())).thenReturn("session-x");

        controller.search(42L, request);

        verify(snapshotPort).save(eq(42L), any(), eq(true), any());
    }

    @Test
    @DisplayName("급행 끄기를 안 보낸 검색(구버전 앱)은 false 로 남는다")
    void excludeExpressAbsentIsFalse() {
        RouteSearchRequest request = request();
        when(searchRouteUseCase.search(request)).thenReturn(result());
        when(snapshotPort.save(any(), any(), anyBoolean(), any())).thenReturn("session-x");

        controller.search(42L, request);

        verify(snapshotPort).save(eq(42L), any(), eq(false), any());
    }

    @Test
    @DisplayName("스냅샷 저장이 실패해도 200 과 기존 응답 필드는 그대로다")
    void snapshotFailureKeepsExistingResponse() {
        RouteSearchRequest request = request();
        when(searchRouteUseCase.search(request)).thenReturn(result());
        when(snapshotPort.save(any(), any(), anyBoolean(), any())).thenThrow(new RuntimeException("redis down"));

        var response = controller.search(null, request);
        RouteSearchResult body = response.getBody();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(body.searchSessionId()).isNull();
        assertThat(body.expiresAt()).isNull();
        // 기존 계약: 경로·개수·outcome 은 그대로, 추가된 건 degraded 한 줄뿐이다.
        assertThat(body.searchType()).isEqualTo(2);
        assertThat(body.subwayCount()).isEqualTo(1);
        assertThat(body.paths()).hasSize(2);
        assertThat(body.meta().outcome()).isEqualTo("full");
        assertThat(body.meta().degraded())
                .containsExactly(new SearchMeta.DegradedComponent("journey_snapshot", "upstream_error"));
    }

    @Test
    @DisplayName("스냅샷을 끄면 응답은 기존과 완전히 동일하다")
    void disabledSnapshotLeavesResponseUnchanged() {
        snapshotProperties.setEnabled(false);
        RouteSearchRequest request = request();
        RouteSearchResult result = result();
        when(searchRouteUseCase.search(request)).thenReturn(result);

        RouteSearchResult body = controller.search(42L, request).getBody();

        assertThat(body).isSameAs(result);
        assertThat(body.searchSessionId()).isNull();
        verifyNoInteractions(snapshotPort);
    }

    // ── 사용 퍼널 기록(V79) ──────────────────────────────────────────────────

    @Test
    void TC_익명_검색도_퍼널_기록을_남긴다() {
        RouteSearchRequest request = request();
        RouteSearchResult result = emptyResult();
        when(searchRouteUseCase.search(request)).thenReturn(result);

        controller.search(null, request);

        verify(searchEventRecorder).record(isNull(), eq(result), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void TC_퍼널_기록은_스냅샷이_붙은_응답을_받는다() {
        RouteSearchRequest request = request();
        when(searchRouteUseCase.search(request)).thenReturn(result());
        when(snapshotPort.save(eq(42L), any(), anyBoolean(), any())).thenReturn("sess-9");

        var response = controller.search(42L, request);

        verify(searchEventRecorder).record(eq(42L),
                org.mockito.ArgumentMatchers.argThat(r -> "sess-9".equals(r.searchSessionId())),
                org.mockito.ArgumentMatchers.anyLong());
        assertThat(response.getBody().searchSessionId()).isEqualTo("sess-9");
    }

    @Test
    void TC_퍼널_기록기가_꺼져_있어도_검색은_그대로() {
        controller = new RouteSearchController(searchRouteUseCase, recordSearchHistoryUseCase,
                new RouteSearchSnapshotRecorder(snapshotPort, snapshotProperties, Clock.fixed(NOW, ZoneOffset.UTC)),
                eventRecorders(null));
        RouteSearchRequest request = request();
        RouteSearchResult result = emptyResult();
        when(searchRouteUseCase.search(request)).thenReturn(result);

        assertThat(controller.search(null, request).getBody()).isSameAs(result);
    }
}
