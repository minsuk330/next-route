package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.RouteStopProjection;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 수락 직전, 중간 하차를 아직 반영할 수 있는지.
 *
 * <p>못박는 것 셋. <b>누르는 사이 지나간 정류장은 거절한다</b>(이 기능에서 가장 나쁜 사고는
 * 이미 지나간 곳에서 내리라고 말하는 것이다), <b>실시간이 없으면 거절한다</b>(시간표 폴백 없음),
 * 그리고 <b>소요는 초 단위 실측</b>이다(스냅샷의 분 단위가 아니다).
 */
@DisplayName("중간 하차 재확인")
class RideTruncationResolverTest {

    private static final long JOURNEY_ID = 7L;
    private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");
    private static final String ROUTE_ID = "BL-RIDE";

    private final BusRouteStopRepository busRouteStopRepository = mock(BusRouteStopRepository.class);
    private final SearchTimeBusQueryPort busQueryPort = mock(SearchTimeBusQueryPort.class);
    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();

    private final RideTruncationResolver resolver = new RideTruncationResolver(
            new BusRideLocator(busRouteStopRepository), busQueryPort, properties);

    @BeforeEach
    void setUp() {
        given(busRouteStopRepository.findStopsByRouteId(ROUTE_ID)).willReturn(routeStops());
    }

    @Test
    @DisplayName("자를 자리는 승차 순번과의 차이고, 소요는 승차 시각부터 잰 실측이다")
    void resolvesIndexAndRideSeconds() {
        givenArrival("ST-13", 13, 300);

        RideTruncationResolver.Resolved resolved =
                resolver.resolve(rideLeg(3), origin(13), NOW).resolved();

        // 승차=10번, 하차 후보=13번 → 구간 정류장 목록에서 세 번째(승차가 0).
        assertThat(resolved.alightStationIndex()).isEqualTo(3);
        // 탄 지 10분 됐고(activatedAt) 5분 뒤 그 정류장에 선다 → 승차→X = 900초.
        assertThat(resolved.rideSeconds()).isEqualTo(900);
        assertThat(resolved.alightAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(resolved.stopName()).isEqualTo("정류장13");
        // 12번을 지났고 13번에서 내린다 → 남은 정류장 하나.
        assertThat(resolved.remainingStops()).isEqualTo(1);
        // 잘린 단계(10→13) 안에서 12번까지 왔다.
        assertThat(resolved.legFraction())
                .isEqualByComparingTo(BigDecimal.valueOf(2).divide(BigDecimal.valueOf(3), 6,
                        java.math.RoundingMode.HALF_UP));
    }

    @Test
    @DisplayName("누르는 사이 지나간 정류장은 거절한다")
    void rejectsPassedStop() {
        // 지금 막 지난 정류장(12번)과 그 앞은 이제 내릴 수 없다.
        assertThat(resolver.resolve(rideLeg(3), origin(12), NOW).failureReason())
                .isEqualTo("alight-passed");
        assertThat(resolver.resolve(rideLeg(3), origin(11), NOW).failureReason())
                .isEqualTo("alight-passed");
    }

    @Test
    @DisplayName("계획 하차 정류장 이후는 이 주행 구간 밖이다")
    void rejectsBeyondPlannedAlight() {
        assertThat(resolver.resolve(rideLeg(3), origin(15), NOW).failureReason())
                .isEqualTo("alight-passed");
    }

    @Test
    @DisplayName("실시간이 막혔거나 내 차가 없으면 거절한다 — 시간표로 대신하지 않는다")
    void rejectsWithoutRealtime() {
        given(busQueryPort.getArrInfoByStop(eq("ST-13"), eq(ROUTE_ID), eq(13), anyLong()))
                .willReturn(SearchTimeBusQueryPort.BusQueryResult.blocked());

        assertThat(resolver.resolve(rideLeg(3), origin(13), NOW).failureReason())
                .isEqualTo("arrival-blocked");

        // 내 뒤의 차(구간 순번 11)만 온다 — 그 차의 도착으로 내리면 사용자는 정류장을 지나친다.
        given(busQueryPort.getArrInfoByStop(eq("ST-13"), eq(ROUTE_ID), eq(13), anyLong()))
                .willReturn(SearchTimeBusQueryPort.BusQueryResult.ok(
                        List.of(arrival("ST-13", 13, 11, 300))));

        assertThat(resolver.resolve(rideLeg(3), origin(13), NOW).failureReason())
                .isEqualTo("no-realtime");
    }

    @Test
    @DisplayName("문 앞으로 갈 시간이 없으면 거절한다")
    void rejectsBelowLeadTime() {
        givenArrival("ST-13", 13, 20);

        assertThat(resolver.resolve(rideLeg(3), origin(13), NOW).failureReason())
                .isEqualTo("below-lead-time");
    }

    @Test
    @DisplayName("수락 리드타임은 제안 리드타임(90초)보다 짧다 — 알림을 보고 바로 누른 수락이 살아야 한다")
    void acceptLeadTimeIsShorterThanProposalLeadTime() {
        // 제안을 만들 때였다면 리드타임(90초) 미만이라 자리 자체가 안 열렸을 시각이다.
        givenArrival("ST-13", 13, 45);

        assertThat(resolver.resolve(rideLeg(3), origin(13), NOW).failed()).isFalse();
    }

    @Test
    @DisplayName("진행 관측이 낡았으면 어디쯤인지 모른다 — 거절한다")
    void rejectsStaleProgress() {
        givenArrival("ST-13", 13, 300);
        JourneyLeg ride = rideLeg(3);
        ReflectionTestUtils.setField(ride, "progressObservedAt", NOW.minusSeconds(600));

        assertThat(resolver.resolve(ride, origin(13), NOW).failureReason())
                .isEqualTo("stale-progress");
    }

    @Test
    @DisplayName("타고 있는 버스 단계가 아니면 자르지 않는다")
    void rejectsWhenNotRidingABus() {
        givenArrival("ST-13", 13, 300);

        assertThat(resolver.resolve(null, origin(13), NOW).failureReason()).isEqualTo("not-riding");

        JourneyLeg pending = rideLeg(3);
        ReflectionTestUtils.setField(pending, "state", JourneyLegState.PENDING);
        assertThat(resolver.resolve(pending, origin(13), NOW).failureReason())
                .isEqualTo("not-riding");

        JourneyLeg subway = rideLeg(3);
        ReflectionTestUtils.setField(subway, "type", JourneyLegType.RIDE_SUBWAY);
        assertThat(resolver.resolve(subway, origin(13), NOW).failureReason())
                .isEqualTo("not-riding");
    }

    @Test
    @DisplayName("원점이 다른 노선을 가리키면 자르지 않는다 — 순번의 뜻이 달라진다")
    void rejectsWhenOriginPointsAtAnotherRoute() {
        RerouteOrigin other = new RerouteOrigin(3, "ST-13", "BL-OTHER", 13,
                RerouteOriginKind.RIDE_INTERIOR);

        assertThat(resolver.resolve(rideLeg(3), other, NOW).failureReason()).isEqualTo("not-riding");
    }

    @Test
    @DisplayName("언제 탔는지 모르면 승차→하차 소요를 만들지 않는다")
    void rejectsWithoutBoardingTime() {
        givenArrival("ST-13", 13, 300);
        JourneyLeg ride = rideLeg(3);
        ReflectionTestUtils.setField(ride, "activatedAt", null);

        assertThat(resolver.resolve(ride, origin(13), NOW).failureReason())
                .isEqualTo("no-boarding-time");
    }

    @Test
    @DisplayName("앱이 남은 정류장 수를 안 보냈으면 위치를 모른다")
    void rejectsWithoutProgress() {
        givenArrival("ST-13", 13, 300);
        JourneyLeg ride = rideLeg(3);
        ReflectionTestUtils.setField(ride, "remainingStops", null);

        assertThat(resolver.resolve(ride, origin(13), NOW).failureReason()).isEqualTo("no-progress");
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /** 승차 10번, 계획 하차 15번. 지금은 12번을 지났다. */
    private JourneyLeg rideLeg(int remainingStops) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(JOURNEY_ID).routeRevision(1).legIndex(3)
                .type(JourneyLegType.RIDE_BUS).state(JourneyLegState.ACTIVE)
                .startStopId("ST-10").endStopId("ST-15")
                .startName("정류장10").endName("정류장15")
                .plannedDurationSec(1500)
                .activatedAt(NOW.minusSeconds(600))
                .build();
        ReflectionTestUtils.setField(leg, "routeId", ROUTE_ID);
        ReflectionTestUtils.setField(leg, "remainingStops", remainingStops);
        ReflectionTestUtils.setField(leg, "progressObservedAt", NOW);
        return leg;
    }

    private RerouteOrigin origin(int seq) {
        return new RerouteOrigin(3, "ST-" + seq, ROUTE_ID, seq, RerouteOriginKind.RIDE_INTERIOR);
    }

    private List<RouteStopProjection> routeStops() {
        List<RouteStopProjection> stops = new ArrayList<>();
        for (int seq = 10; seq <= 16; seq++) {
            stops.add(stop(seq));
        }
        return stops;
    }

    private RouteStopProjection stop(int seq) {
        return new RouteStopProjection() {
            @Override public Integer getSeq() { return seq; }
            @Override public String getStopId() { return "ST-" + seq; }
            @Override public String getStopName() { return "정류장" + seq; }
            @Override public Double getLatitude() { return 37.0 + seq / 100.0; }
            @Override public Double getLongitude() { return 127.0 + seq / 100.0; }
            @Override public String getDirection() { return "방면"; }
        };
    }

    /** 내 차(구간 순번 12 = 지금 내 위치)가 {@code afterSec} 뒤에 그 정류장에 선다. */
    private void givenArrival(String stopId, int seq, int afterSec) {
        given(busQueryPort.getArrInfoByStop(eq(stopId), eq(ROUTE_ID), eq(seq), anyLong()))
                .willReturn(SearchTimeBusQueryPort.BusQueryResult.ok(
                        List.of(arrival(stopId, seq, 12, afterSec))));
    }

    /** 도착 정보 한 건. 시각은 관측 시각({@code dataTimestamp}) + 남은 소요로 만들어진다. */
    private BusArrivalInfo arrival(String stopId, int seq, int sectionOrder, int afterSec) {
        return new BusArrivalInfo(
                ROUTE_ID, "RIDE", "노선", stopId, "01234", "정류장" + seq, seq, "방면", 3, 10,
                observedAt(NOW), "N", "N", "05:00", "23:00", afterSec + "초 후", "VEH-1",
                "서울70사1234", 1, sectionOrder, "직전정류장", "0", "0", "0", afterSec, null, null, null,
                null, null, null, null, afterSec, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null);
    }

    private String observedAt(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(ZonedDateTime.ofInstant(instant, ZoneId.of("Asia/Seoul")));
    }
}
