package watoo.grd.nextroute.application.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.port.out.RouteOutOfServiceException;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.EvaluationMode;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.RouteEvaluation;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.TravelTimeQuery;
import watoo.grd.nextroute.application.notification.service.RouteServiceWindowChecker.Availability;
import watoo.grd.nextroute.application.route.dto.LaneResult;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;
import watoo.grd.nextroute.application.route.service.TransferStopResolver.RouteResolution;
import watoo.grd.nextroute.application.route.service.TransferStopResolver.SeqResolution;
import watoo.grd.nextroute.application.route.service.TransferStopResolver.StopResolution;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertReliability;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RouteSearchTravelTimeEstimatorTest {

    @Mock SearchRouteUseCase searchRouteUseCase;
    @Mock TransferStopResolver resolver;
    @Mock RouteServiceWindowChecker serviceWindowChecker;

    RouteArrivalAlertProperties properties = new RouteArrivalAlertProperties();

    RouteSearchTravelTimeEstimator estimator() {
        return new RouteSearchTravelTimeEstimator(searchRouteUseCase, resolver, serviceWindowChecker, properties);
    }

    private TravelTimeQuery query() {
        return new TravelTimeQuery(127.0, 37.5, "출발", 127.1, 37.6, "도착", LocalDateTime.now(), 5);
    }

    // ── DTO 빌더 ────────────────────────────────────────────────────────────

    private SubPathResult bus(int sectionTimeMin, List<TransferArrival> arrivals) {
        LaneResult lane = new LaneResult("간선", "146", null, 2, 1, "BL1", null);
        return new SubPathResult(2, sectionTimeMin, 1000.0,
                List.of(lane), List.of(), "정류장A", "정류장B",
                127.0, 37.5, 127.05, 37.55,
                null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, "STA1", "STA2", null, null, null,
                null, arrivals, null, null, null, null);
    }

    private SubPathResult busTwoLane(int sectionTimeMin, List<TransferArrival> arrivals) {
        LaneResult lane0 = new LaneResult("간선", "146", null, 2, 1, "BL1", null);
        LaneResult lane1 = new LaneResult("지선", "471", null, 2, 2, "BL2", null);
        return new SubPathResult(2, sectionTimeMin, 1000.0,
                List.of(lane0, lane1), List.of(), "정류장A", "정류장B",
                127.0, 37.5, 127.05, 37.55,
                null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, "STA1", "STA2", null, null, null,
                null, arrivals, null, null, null, null);
    }

    private SubPathResult walk(int walkSeconds) {
        return new SubPathResult(3, Math.max(1, walkSeconds / 60), 500.0,
                List.of(), List.of(), "출발", "정류장A",
                127.0, 37.5, 127.0, 37.5,
                null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(), null, null, null, null, null,
                walkSeconds, null, null, null, null, null);
    }

    private TransferArrival arrival(int laneIndex, TransferArrival.Status status, long waitSeconds) {
        Instant base = Instant.parse("2026-06-23T00:00:00Z");
        return new TransferArrival("route1", laneIndex, TransferArrival.Source.REALTIME, status,
                base, base, base.plusSeconds(waitSeconds), waitSeconds, "veh1", null, null, false);
    }

    private RouteSearchResult result(SubPathResult... subs) {
        PathInfo info = new PathInfo(30, 0, 0, 0, null, null, null);
        PathResult path = new PathResult(1, info, List.of(subs), List.of());
        return new RouteSearchResult(0, 0, 0, 0, 0, List.of(path));
    }

    private void stubResolveOk(Integer term) {
        given(resolver.resolveStop(any())).willReturn(Optional.of(new StopResolution("stop1")));
        given(resolver.resolveRoute(any(), any())).willReturn(Optional.of(new RouteResolution("route1", "146", false, term)));
        given(resolver.resolveSeq(any(), any())).willReturn(new SeqResolution(List.of(3)));
    }

    // ── STATIC ───────────────────────────────────────────────────────────────

    @Test
    void static_busSegment_usesTermHalf() {
        // sectionTime 20분 + term 12분/2 = 6분 → 26분 = 1560s
        given(searchRouteUseCase.searchWithoutRealtime(any())).willReturn(result(bus(20, List.of())));
        stubResolveOk(12);
        given(serviceWindowChecker.judge(any(), any(), any())).willReturn(Availability.AVAILABLE);

        RouteEvaluation e = estimator().evaluate(query(), EvaluationMode.STATIC);

        assertThat(e.durationSec()).isEqualTo(20 * 60 + 6 * 60);
        assertThat(e.maxHeadwayMinutes()).isEqualTo(12);
        assertThat(e.selectedPath().boardings()).hasSize(1);
        assertThat(e.reliability()).isEqualTo(RouteAlertReliability.MEDIUM);
    }

    @Test
    void static_termNull_usesDefaultHeadway() {
        // term null → default 10분/2 = 5분 → 20+5 = 25분 = 1500s
        given(searchRouteUseCase.searchWithoutRealtime(any())).willReturn(result(bus(20, List.of())));
        stubResolveOk(null);
        given(serviceWindowChecker.judge(any(), any(), any())).willReturn(Availability.AVAILABLE);

        RouteEvaluation e = estimator().evaluate(query(), EvaluationMode.STATIC);

        assertThat(e.durationSec()).isEqualTo(20 * 60 + 5 * 60);
    }

    @Test
    void static_walkOnly_noBus_reliabilityHigh() {
        given(searchRouteUseCase.searchWithoutRealtime(any())).willReturn(result(walk(600)));

        RouteEvaluation e = estimator().evaluate(query(), EvaluationMode.STATIC);

        assertThat(e.durationSec()).isEqualTo(600);
        assertThat(e.reliability()).isEqualTo(RouteAlertReliability.HIGH);
        assertThat(e.selectedPath().boardings()).isEmpty();
    }

    // ── REALTIME ───────────────────────────────────────────────────────────────

    @Test
    void realtime_usesWaitSeconds() {
        // 실시간 대기 180s + ride 1200s = 1380s. search() 사용.
        given(searchRouteUseCase.search(any()))
                .willReturn(result(bus(20, List.of(arrival(0, TransferArrival.Status.AVAILABLE, 180)))));
        stubResolveOk(12);
        given(serviceWindowChecker.judge(any(), any(), any())).willReturn(Availability.AVAILABLE);

        RouteEvaluation e = estimator().evaluate(query(), EvaluationMode.REALTIME);

        assertThat(e.durationSec()).isEqualTo(1200 + 180);
        assertThat(e.reliability()).isEqualTo(RouteAlertReliability.HIGH);
    }

    @Test
    void realtime_usesTheChosenLanesRideSeconds() {
        // 구간 20분(대표 lane0 = 1200s). lane1 은 같은 정류장 사이를 1500s 에 가고 먼저 온다(대기 60s vs 600s)
        // → 고른 lane1 의 탑승시간으로 잰다: 1500 + 60 (bus-lane-selection PR-1)
        LaneResult lane0 = new LaneResult("간선", "146", null, 2, 1, "BL1", null, 1200);
        LaneResult lane1 = new LaneResult("지선", "471", null, 2, 2, "BL2", null, 1500);
        SubPathResult twoLane = new SubPathResult(2, 20, 1000.0,
                List.of(lane0, lane1), List.of(), "정류장A", "정류장B",
                127.0, 37.5, 127.05, 37.55,
                null, null, null, null, null,
                null, null, null, null, null, null, null,
                null, "STA1", "STA2", null, null, null,
                null, List.of(arrival(0, TransferArrival.Status.AVAILABLE, 600),
                        arrival(1, TransferArrival.Status.AVAILABLE, 60)), null, null, null, null);
        given(searchRouteUseCase.search(any())).willReturn(result(twoLane));
        stubResolveOk(12);
        given(serviceWindowChecker.judge(any(), any(), any())).willReturn(Availability.AVAILABLE);

        RouteEvaluation e = estimator().evaluate(query(), EvaluationMode.REALTIME);

        assertThat(e.durationSec()).isEqualTo(1500 + 60);
    }

    @Test
    void realtime_noValidLane_fallbackTermHalf_reliabilityLow() {
        given(searchRouteUseCase.search(any()))
                .willReturn(result(bus(20, List.of(arrival(0, TransferArrival.Status.NO_VEHICLE, 0)))));
        stubResolveOk(12);
        given(serviceWindowChecker.judge(any(), any(), any())).willReturn(Availability.AVAILABLE);

        RouteEvaluation e = estimator().evaluate(query(), EvaluationMode.REALTIME);

        // fallback term/2 = 6분 → 20+6 = 26분
        assertThat(e.durationSec()).isEqualTo(20 * 60 + 6 * 60);
        assertThat(e.reliability()).isEqualTo(RouteAlertReliability.LOW);
    }

    @Test
    void realtime_preferredOverFallback_evenIfFallbackShorter() {
        // lane0=NO_VEHICLE(fallback term12/2=6분), lane1=AVAILABLE 8분. 실시간이 우선 → 8분 선택.
        given(searchRouteUseCase.search(any())).willReturn(result(busTwoLane(20, List.of(
                arrival(0, TransferArrival.Status.NO_VEHICLE, 0),
                arrival(1, TransferArrival.Status.AVAILABLE, 480)))));
        stubResolveOk(12);
        given(serviceWindowChecker.judge(any(), any(), any())).willReturn(Availability.AVAILABLE);

        RouteEvaluation e = estimator().evaluate(query(), EvaluationMode.REALTIME);

        assertThat(e.durationSec()).isEqualTo(20 * 60 + 480);   // fallback 6분(360s) 아님
        assertThat(e.reliability()).isEqualTo(RouteAlertReliability.HIGH);
    }

    // ── 후보 선택 / 예외 ─────────────────────────────────────────────────────

    @Test
    void picksMinDurationAcrossPaths() {
        PathInfo info = new PathInfo(30, 0, 0, 0, null, null, null);
        PathResult slow = new PathResult(1, info, List.of(bus(40, List.of())), List.of());
        PathResult fast = new PathResult(1, info, List.of(bus(20, List.of())), List.of());
        given(searchRouteUseCase.searchWithoutRealtime(any()))
                .willReturn(new RouteSearchResult(0, 0, 0, 0, 0, List.of(slow, fast)));
        stubResolveOk(10);
        given(serviceWindowChecker.judge(any(), any(), any())).willReturn(Availability.AVAILABLE);

        RouteEvaluation e = estimator().evaluate(query(), EvaluationMode.STATIC);

        assertThat(e.durationSec()).isEqualTo(20 * 60 + 5 * 60); // fast path
    }

    @Test
    void allOutOfService_throws() {
        given(searchRouteUseCase.searchWithoutRealtime(any())).willReturn(result(bus(20, List.of())));
        stubResolveOk(10);
        given(serviceWindowChecker.judge(any(), any(), any())).willReturn(Availability.OUT_OF_SERVICE);

        assertThatThrownBy(() -> estimator().evaluate(query(), EvaluationMode.STATIC))
                .isInstanceOf(RouteOutOfServiceException.class);
    }

    @Test
    void emptyPaths_throws() {
        given(searchRouteUseCase.searchWithoutRealtime(any()))
                .willReturn(new RouteSearchResult(0, 0, 0, 0, 0, List.of()));

        assertThatThrownBy(() -> estimator().evaluate(query(), EvaluationMode.STATIC))
                .isInstanceOf(IllegalStateException.class);
    }
}
