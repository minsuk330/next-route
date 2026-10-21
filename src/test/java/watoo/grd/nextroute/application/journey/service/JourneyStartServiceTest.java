package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.Fare;
import watoo.grd.nextroute.application.route.routing.LegGeometry;
import watoo.grd.nextroute.application.route.routing.RealtimeCoverage;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;
import watoo.grd.nextroute.application.route.service.LegacyRouteResponseMapper;
import watoo.grd.nextroute.application.route.service.WalkCoordResolver;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.bus;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.busLane;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.walk;

/**
 * 시작 전에 걸러야 하는 것들과, 잠금을 뚫고 들어온 충돌을 어떻게 번역하는지를 고정한다.
 *
 * <p>저장 자체는 실제 DB 가 있어야 확인할 수 있어 {@code JourneyStartIT} 가 맡는다. 여기서는
 * 트랜잭션 밖 판단만 본다.
 */
class JourneyStartServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T09:00:00Z");
    private static final Long USER_ID = 7L;

    private RouteSearchSnapshotPort snapshotPort;
    private JourneyStartTxService txService;
    private JourneyQueryService queryService;
    private JourneyTransitIdEnricher transitIdEnricher;
    private JourneyStartService service;

    @BeforeEach
    void setUp() {
        snapshotPort = mock(RouteSearchSnapshotPort.class);
        txService = mock(JourneyStartTxService.class);
        queryService = mock(JourneyQueryService.class);
        transitIdEnricher = mock(JourneyTransitIdEnricher.class);
        given(transitIdEnricher.enrich(any())).willAnswer(returnsFirstArg());
        service = new JourneyStartService(
                snapshotPort,
                new JourneyCompiler(new StopIdentityResolver()),
                transitIdEnricher,
                new JourneyGuidanceInfoEnricher(org.mockito.Mockito.mock(watoo.grd.nextroute.domain.bus.repository.BusStopRepository.class), org.mockito.Mockito.mock(watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore.class), new watoo.grd.nextroute.application.subway.service.SubwayEntranceIndex(), new watoo.grd.nextroute.application.subway.service.SubwayFastAlightIndex(), new watoo.grd.nextroute.application.subway.service.SubwayDoorSideIndex(), new watoo.grd.nextroute.application.subway.service.SubwayLineEnds(org.mockito.Mockito.mock(watoo.grd.nextroute.application.subway.service.SubwayLinePatternService.class))),
                txService,
                new JourneyStartConflictTranslator(queryService),
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private StartJourneyCommand command() {
        return new StartJourneyCommand("sess-1", "0", false);
    }

    private PathResult usablePath() {
        return new PathResult(1,
                new PathInfo(30, 1500, 500, 0, "정류장A", "정류장F", null),
                List.of(
                        walk(5).names("집", "정류장A").build(),
                        bus(20).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(busLane("370", "BL-370")).build()),
                List.of());
    }

    private JourneySnapshot snapshot(Long journeyId) {
        return JourneySnapshot.of(journeyId, JourneyStatus.ACTIVE, JourneyKind.NAVIGATION, 1L, 0L, 1, 0,
                NOW.plusSeconds(180), null, NOW, List.of());
    }

    private void givenStoredPath(Long ownerUserId, PathResult path) {
        given(snapshotPort.find("sess-1", "0")).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(ownerUserId, path, NOW.plusSeconds(600))));
    }

    // ── 시작 전 검사 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("스냅샷이 없으면 만료로 거절하고 저장을 시도하지 않는다")
    void missingSnapshot() {
        given(snapshotPort.find("sess-1", "0")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(USER_ID, command()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(JourneyErrorCode.SNAPSHOT_EXPIRED));
        verify(txService, never()).start(anyLong(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("남의 검색 결과면 거절한다")
    void otherUsersSnapshot() {
        givenStoredPath(99L, usablePath());

        assertThatThrownBy(() -> service.start(USER_ID, command()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(JourneyErrorCode.SNAPSHOT_OWNER_MISMATCH));
        verify(txService, never()).start(anyLong(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("로그인 없이 검색한 결과로는 시작할 수 있다")
    void anonymousSnapshot_allowed() {
        givenStoredPath(null, usablePath());
        given(txService.start(eq(USER_ID), any(), any(), any(), eq(false), eq(NOW), eq(JourneyKind.NAVIGATION), eq(false)))
                .willReturn(snapshot(1L));

        assertThat(service.start(USER_ID, command()).journeyId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("쪼갤 구간이 없으면 만료와 구분해 거절한다")
    void emptyPath_isNotExpiry() {
        givenStoredPath(USER_ID, new PathResult(1, null, List.of(), List.of()));

        assertThatThrownBy(() -> service.start(USER_ID, command()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(JourneyErrorCode.JOURNEY_COMPILE_FAILED));
    }

    @Test
    @DisplayName("경로 원본과 목적지 이름을 함께 넘긴다")
    void passesSnapshotAndDestination() {
        givenStoredPath(USER_ID, usablePath());
        given(txService.start(anyLong(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean()))
                .willReturn(snapshot(1L));

        service.start(USER_ID, command());

        verify(txService).start(eq(USER_ID), any(),
                org.mockito.ArgumentMatchers.argThat(node -> node.get("pathType").asInt() == 1),
                org.mockito.ArgumentMatchers.argThat(node -> "정류장F".equals(node.get("name").asText())),
                eq(false), eq(NOW), eq(JourneyKind.NAVIGATION), eq(false));
    }

    @Test
    @DisplayName("스냅샷의 급행 끄기(검색 요청값)를 여정 저장에 그대로 넘긴다 — 재탐색이 세션 값으로 찾는다(subway-express S5)")
    void passesExcludeExpressFromSnapshot() {
        given(snapshotPort.find("sess-1", "0")).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(USER_ID, usablePath(), NOW.plusSeconds(600), true)));
        given(txService.start(anyLong(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean()))
                .willReturn(snapshot(1L));

        service.start(USER_ID, command());

        verify(txService).start(eq(USER_ID), any(), any(), any(), eq(false), eq(NOW),
                eq(JourneyKind.NAVIGATION), eq(true));
    }

    // ── 충돌 번역 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("사람당 하나 인덱스 위반은 409 로 바꾸고 현재 여정을 실어 준다")
    void uniqueViolation_becomesConflict() {
        givenStoredPath(USER_ID, usablePath());
        given(txService.start(anyLong(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean()))
                .willThrow(activeJourneyViolation());
        given(queryService.getActive(USER_ID)).willReturn(Optional.of(snapshot(42L)));

        assertThatThrownBy(() -> service.start(USER_ID, command()))
                .isInstanceOf(ActiveJourneyExistsException.class)
                .satisfies(e -> assertThat(((ActiveJourneyExistsException) e).getCurrent().journeyId())
                        .isEqualTo(42L));
    }

    @Test
    @DisplayName("다른 무결성 오류는 409 로 숨기지 않는다")
    void otherIntegrityError_notTranslated() {
        givenStoredPath(USER_ID, usablePath());
        DataIntegrityViolationException original = violation("fk_journey_leg_journey", "23503");
        given(txService.start(anyLong(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean()))
                .willThrow(original);

        assertThatThrownBy(() -> service.start(USER_ID, command()))
                .as("FK 위반이 '이미 여정이 있습니다'로 둔갑하면 진짜 버그가 묻힌다")
                .isSameAs(original);
        verify(queryService, never()).getActive(anyLong());
    }

    @Test
    @DisplayName("충돌 상대가 그새 끝났으면 원래 오류를 그대로 올린다")
    void conflictGone_rethrows() {
        givenStoredPath(USER_ID, usablePath());
        DataIntegrityViolationException original = activeJourneyViolation();
        given(txService.start(anyLong(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean()))
                .willThrow(original);
        given(queryService.getActive(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(USER_ID, command())).isSameAs(original);
    }

    private DataIntegrityViolationException activeJourneyViolation() {
        return violation("uq_journey_active_per_user", "23505");
    }

    private DataIntegrityViolationException violation(String constraint, String sqlState) {
        ConstraintViolationException cause = new ConstraintViolationException(
                "violation", new SQLException("violation", sqlState), constraint);
        return new DataIntegrityViolationException("could not execute statement", cause);
    }

    /** 컴파일러가 실제로 쓰이는지 확인하는 최소 경로. */
    @Test
    @DisplayName("쪼갠 결과를 트랜잭션으로 넘긴다")
    void passesCompiledPlan() {
        givenStoredPath(USER_ID, usablePath());
        given(txService.start(anyLong(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean()))
                .willReturn(snapshot(1L));

        service.start(USER_ID, command());

        verify(txService).start(eq(USER_ID),
                org.mockito.ArgumentMatchers.argThat(plan -> plan.legs().size() == 3),
                any(), any(), eq(false), eq(NOW), eq(JourneyKind.NAVIGATION), eq(false));
    }

    // ── 장소 목적지 걷기 이름(#468) ──────────────────────────────────────────

    /** 강남(2호선) → 삼성, 앞뒤 걷기. OTP 결과 모양 그대로 걷기 leg 에는 정류장이 없다. */
    private static RoutingResult otpGangnamToCoex() {
        RoutingLeg walkIn = otpWalkLeg();
        RoutingLeg subway = new RoutingLeg(RoutingMode.SUBWAY, "SUBWAY", "1", "1:A1", "1:S2", "서울2호선",
                "1:T", null, NOW, NOW.plusSeconds(600), 600,
                List.of(new StopOccurrence("1:RS_222", null, null, 0, 127.0276, 37.4979, "강남"),
                        new StopOccurrence("1:RS_219", null, null, 1, 127.0630, 37.5088, "삼성")),
                List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
        RoutingLeg walkOut = otpWalkLeg();
        RoutingItinerary it = new RoutingItinerary(NOW.plusSeconds(1200), 600, 0, 600, 0, false,
                List.of(), Fare.unknown(), List.of(walkIn, subway, walkOut));
        return new RoutingResult(RoutingProvider.OTP, "v", "202503", "g1",
                null, null, NOW, List.of(), List.of(it));
    }

    private static RoutingLeg otpWalkLeg() {
        return new RoutingLeg(RoutingMode.WALK, null, null, null, null, null, null, null,
                NOW, NOW.plusSeconds(300), 300, List.of(), List.of(), LegGeometry.missing("walk"),
                Enrichment.staticOnly(RealtimeCoverage.NONE), Set.of());
    }

    /**
     * 검색이 채운 걷기 끝점 이름이 스냅샷(JSON) 왕복을 거쳐 여정 단계까지 간다. 앱은 여정 시작 때 이름을 다시 보내지
     * 않으므로({@code searchSessionId, pathKey} 만) 이 사슬이 끊기면 '출발지'·'도착지'로 보인다.
     */
    @Test
    @DisplayName("장소 목적지 OTP 경로: 첫 걷기 출발 이름과 마지막 걷기 목적지 이름이 여정 단계에 실린다")
    void otpPlaceDestination_walkLegsCarryEndpointNames() throws Exception {
        RouteSearchResult searched = new WalkCoordResolver().fillWalkEndpoints(
                new LegacyRouteResponseMapper(mock(OtpSeoulIdentityResolver.class)).toLegacy(otpGangnamToCoex()),
                127.0270, 37.4985, "현재 위치", 127.0590, 37.5115, "코엑스");
        // 스냅샷 저장소(RedisRouteSearchSnapshotAdapter)와 같은 JSON 왕복 — 저장 형식에서 이름이 살아남는지까지 본다.
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        RouteSearchResult stored = json.readValue(json.writeValueAsString(searched), RouteSearchResult.class);
        givenStoredPath(USER_ID, stored.paths().get(0));
        given(txService.start(anyLong(), any(), any(), any(), anyBoolean(), any(), any(), anyBoolean())).willReturn(snapshot(1L));

        service.start(USER_ID, command());

        ArgumentCaptor<JourneyPlan> plan = ArgumentCaptor.forClass(JourneyPlan.class);
        verify(txService).start(eq(USER_ID), plan.capture(), any(), any(), eq(false), eq(NOW), eq(JourneyKind.NAVIGATION), eq(false));
        List<JourneyLegDraft> legs = plan.getValue().legs();
        assertThat(legs).extracting(JourneyLegDraft::type).containsExactly(
                JourneyLegType.WALK, JourneyLegType.WAIT_SUBWAY, JourneyLegType.RIDE_SUBWAY, JourneyLegType.WALK);
        assertThat(legs.get(0).startName()).isEqualTo("현재 위치");
        assertThat(legs.get(0).endName()).isEqualTo("강남");
        assertThat(legs.get(3).startName()).isEqualTo("삼성");
        assertThat(legs.get(3).endName()).isEqualTo("코엑스");
        assertThat(legs.get(3).endX()).isEqualTo(127.0590);
        assertThat(legs.get(3).endY()).isEqualTo(37.5115);
    }
}
