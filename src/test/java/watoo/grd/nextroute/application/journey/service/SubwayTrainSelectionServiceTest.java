package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneySubwaySelectionProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayTrainCandidate;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.service.ServiceDateResolver;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("지하철 열차 선택")
class SubwayTrainSelectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");
    private static final Long USER = 1L;
    private static final Long JOURNEY = 7L;

    private final JourneySessionRepository sessionRepository = mock(JourneySessionRepository.class);
    private final JourneyLegRepository legRepository = mock(JourneyLegRepository.class);
    private final JourneyLegRouteOptionRepository optionRepository = mock(JourneyLegRouteOptionRepository.class);
    private final SubwayRealtimeArrivalReader arrivalReader = mock(SubwayRealtimeArrivalReader.class);
    private final SubwayTrainSelectionTxService txService = mock(SubwayTrainSelectionTxService.class);
    private final ServiceDateResolver serviceDateResolver = mock(ServiceDateResolver.class);
    private final watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort trainLogPort =
            mock(watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort.class);
    private final watoo.grd.nextroute.application.subway.port.out.SubwayArrivalDepartureLogPort arrivalDepartureLog =
            mock(watoo.grd.nextroute.application.subway.port.out.SubwayArrivalDepartureLogPort.class);
    private final JourneySubwaySelectionProperties properties = new JourneySubwaySelectionProperties();
    private SubwayTrainSelectionService service;
    private JourneySession session;
    private JourneyLeg wait;
    private JourneyLeg ride;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        session = JourneySession.builder().userId(USER).startedAt(NOW).guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(new ObjectMapper().createObjectNode()).build();
        ReflectionTestUtils.setField(session, "id", JOURNEY);
        wait = leg(0, JourneyLegType.WAIT_SUBWAY);
        ride = leg(1, JourneyLegType.RIDE_SUBWAY);
        ReflectionTestUtils.setField(wait, "selectedRouteOptionId", 99L);
        JourneyLegRouteOption option = mock(JourneyLegRouteOption.class);
        given(option.getWayCode()).willReturn(0);
        given(optionRepository.findById(99L)).willReturn(Optional.of(option));
        given(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY, USER)).willReturn(Optional.of(session));
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY, 1))
                .willReturn(List.of(wait, ride));
        given(arrivalReader.arrivalsByStation("1002", "1002000222", "강남", 0)).willReturn(List.of(
                arrival("2390", "성수", "일반", 90), arrival("2392", "성수", "급행", 330),
                arrival("2394", "성수", null, null)));
        given(serviceDateResolver.resolve(any(Instant.class))).willReturn(LocalDate.parse("2026-10-07"));
        service = new SubwayTrainSelectionService(properties, sessionRepository, legRepository, optionRepository,
                arrivalReader, serviceDateResolver,
                new watoo.grd.nextroute.application.subway.service.SubwayDepartureLookup(
                        trainLogPort, arrivalDepartureLog, serviceDateResolver),
                txService, new SimpleMeterRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("후보 2대, 급행 표시")
    void candidates() {
        List<SubwayTrainCandidate> candidates = service.candidates(USER, JOURNEY);

        assertThat(candidates).extracting(SubwayTrainCandidate::trainNo).containsExactly("2390", "2392");
        assertThat(candidates.get(1).express()).isTrue();
        assertThat(candidates.get(0).express()).isFalse();
    }

    @Test
    @DisplayName("선택: 4칸(운행일·노선·방향·번호)을 채워 트랜잭션으로 넘긴다. 3번째 열차도 목록에 있으면 받는다")
    void select() {
        service.select(USER, JOURNEY, "2394", 1);

        verify(txService).bind(eq(ride), eq(new SubwayTrainSelectionTxService.ChosenTrain(
                "2394", LocalDate.parse("2026-10-07"), "1002", "내선")));
    }

    @Test
    @DisplayName("목록에 없는 열차 422")
    void notInCandidates() {
        assertThatThrownBy(() -> service.select(USER, JOURNEY, "9999", 1))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRAIN_NOT_IN_CANDIDATES);
        verify(txService, never()).bind(any(), any());
    }

    @Test
    @DisplayName("개정본이 다르면 409 STALE_ROUTE_REVISION")
    void staleRevision() {
        assertThatThrownBy(() -> service.select(USER, JOURNEY, "2390", 2))
                .isInstanceOf(StaleRouteRevisionException.class);
    }

    @Test
    @DisplayName("지하철 대기·탑승이 아닌 단계(내린 뒤 도보)면 409 NOT_WAITING_FOR_SUBWAY")
    void notWaiting() {
        JourneyLeg walk = leg(2, JourneyLegType.WALK);
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY, 1))
                .willReturn(List.of(wait, ride, walk));
        session.moveToLeg(2);

        assertThatThrownBy(() -> service.candidates(USER, JOURNEY))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.NOT_WAITING_FOR_SUBWAY);
    }

    @Test
    @DisplayName("탑승 중이면 곧 올 열차(뒤차)를 읽지 않고 떠난 열차만 후보로 낸다")
    void ridingOffersOnlyDeparted() {
        session.moveToLeg(1);
        given(arrivalReader.justDepartedTrainNos("1002", "1002000222", 0)).willReturn(List.of("2386"));

        List<SubwayTrainCandidate> candidates = service.candidates(USER, JOURNEY);

        assertThat(candidates).extracting(SubwayTrainCandidate::trainNo).containsExactly("2386");
        assertThat(candidates.get(0).kind()).isEqualTo(SubwayTrainCandidate.Kind.DEPARTED);
        verify(arrivalReader, never()).arrivalsByStation(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("방향을 모르면 후보 없음(반대 방향 열차를 집지 않는다)")
    void unknownDirection() {
        given(optionRepository.findById(99L)).willReturn(Optional.empty());

        assertThat(service.candidates(USER, JOURNEY)).isEmpty();
        verify(arrivalReader, never()).arrivalsByStation(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("플래그 off 면 404, 아무것도 읽지 않는다")
    void disabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> service.candidates(USER, JOURNEY))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
        verifyNoInteractions(sessionRepository, arrivalReader, txService);
    }

    @Test
    @DisplayName("남의 여정은 404")
    void othersJourney() {
        given(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(anyLong(), eq(2L))).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.candidates(2L, JOURNEY))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.JOURNEY_NOT_FOUND);
    }

    @Test
    @DisplayName("방금 떠난 열차(#299)도 고를 수 있다 — 출발 로그에 있으면 그 출발의 운행일로 확정")
    void departedTrain() {
        // 03:58 KST 에 떠난 열차를 04:05 에 고른다 → 운행일은 전날(04:00 경계)
        given(serviceDateResolver.resolve(any(Instant.class))).willAnswer(inv ->
                ((Instant) inv.getArgument(0)).isBefore(NOW.minusSeconds(300))
                        ? LocalDate.parse("2026-10-06") : LocalDate.parse("2026-10-07"));
        given(trainLogPort.findDepartures(eq(LocalDate.parse("2026-10-07")), eq("1002"), eq("내선"),
                eq("1002000222"), any(), any())).willReturn(List.of());
        given(trainLogPort.findDepartures(eq(LocalDate.parse("2026-10-06")), eq("1002"), eq("내선"),
                eq("1002000222"), any(), any())).willReturn(List.of(departure("2229")));

        service.select(USER, JOURNEY, "2229", 1);

        verify(txService).bind(eq(ride), eq(new SubwayTrainSelectionTxService.ChosenTrain(
                "2229", LocalDate.parse("2026-10-06"), "1002", "내선", true)));
    }

    @Test
    @DisplayName("#317 위치 API 없는 노선: 도착 API 로 본 떠난 열차도 고를 수 있다(전엔 422)")
    void departedFromArrivalFeedIsSelectable() {
        given(serviceDateResolver.resolve(any(Instant.class))).willReturn(LocalDate.parse("2026-10-07"));
        given(trainLogPort.findDepartures(any(), any(), any(), any(), any(), any())).willReturn(List.of());
        given(arrivalDepartureLog.find(eq(LocalDate.parse("2026-10-07")), eq("1002"), eq("내선"), eq("1002000222"),
                any(), any())).willReturn(List.of(new watoo.grd.nextroute.application.subway.port.out
                .SubwayArrivalDepartureLogPort.Row("K4021", "인천", "일반", NOW.minusSeconds(120))));

        service.select(USER, JOURNEY, "K4021", 1);

        verify(txService).bind(eq(ride), eq(new SubwayTrainSelectionTxService.ChosenTrain(
                "K4021", LocalDate.parse("2026-10-07"), "1002", "내선", true)));
    }

    @Test
    @DisplayName("도착 목록에도 최근 출발에도 없으면 422, 출발 로그를 못 읽어도 422")
    void neitherArrivingNorDeparted() {
        given(trainLogPort.findDepartures(any(), any(), any(), any(), any(), any())).willReturn(List.of());
        assertThatThrownBy(() -> service.select(USER, JOURNEY, "9999", 1))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRAIN_NOT_IN_CANDIDATES);

        given(trainLogPort.findDepartures(any(), any(), any(), any(), any(), any()))
                .willThrow(new IllegalStateException("redis down"));
        assertThatThrownBy(() -> service.select(USER, JOURNEY, "2229", 1))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRAIN_NOT_IN_CANDIDATES);
        verify(txService, never()).bind(any(), any());
    }

    @Test
    @DisplayName("노선도와 같은 원천(수집기 캐시)에 있는 열차는 받는다 — 역별 실시간 호출에 없어도")
    void cachedTrainIsSelectableWithoutLiveCall() {
        given(arrivalReader.arrivals("1002", "1002000222", 0)).willReturn(List.of(arrival("2398", "성수", "일반", 40)));

        service.select(USER, JOURNEY, "2398", 1);

        verify(txService).bind(eq(ride), eq(new SubwayTrainSelectionTxService.ChosenTrain(
                "2398", LocalDate.parse("2026-10-07"), "1002", "내선")));
        verify(arrivalReader, never()).arrivalsByStation(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("곧 도착이던 열차가 고르는 사이 출발(arvlCd=2)했고 출발 기록은 아직 없어도 받는다(전엔 422)")
    void justDepartedBeforeDepartureLogIsSelectable() {
        given(trainLogPort.findDepartures(any(), any(), any(), any(), any(), any())).willReturn(List.of());
        given(arrivalReader.justDepartedTrainNos("1002", "1002000222", 0)).willReturn(List.of("2388"));

        service.select(USER, JOURNEY, "2388", 1);

        verify(txService).bind(eq(ride), eq(new SubwayTrainSelectionTxService.ChosenTrain(
                "2388", LocalDate.parse("2026-10-07"), "1002", "내선", true)));
    }

    @Test
    @DisplayName("후보는 노선도 원천(캐시)이 먼저, 비었을 때만 역별 실시간 호출")
    void candidatesPreferCache() {
        given(arrivalReader.arrivals("1002", "1002000222", 0)).willReturn(List.of(arrival("2398", "성수", "일반", 40)));

        assertThat(service.candidates(USER, JOURNEY)).extracting(SubwayTrainCandidate::trainNo)
                .containsExactly("2398");
        verify(arrivalReader, never()).arrivalsByStation(anyString(), anyString(), anyString(), anyInt());
    }

    private static watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort.TrainDeparture departure(
            String trainNo) {
        Instant mid = NOW.minusSeconds(420);
        return new watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort.TrainDeparture(trainNo,
                new watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent(
                        watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent.Type.DEPARTED, "1002000222", "강남",
                        mid.minusSeconds(75), mid.plusSeconds(75),
                        watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent.Quality.CORROBORATED,
                        "1002000211", watoo.grd.nextroute.application.subway.dto.SubwayExpressType.NORMAL));
    }

    private static JourneyLeg leg(int index, JourneyLegType type) {
        JourneyLeg leg = JourneyLeg.builder().journeyId(JOURNEY).routeRevision(1).legIndex(index).type(type)
                .state(index == 0 ? JourneyLegState.ACTIVE : JourneyLegState.PENDING)
                .startStopId("1002000222").startName("강남").build();
        ReflectionTestUtils.setField(leg, "id", 100L + index);
        ReflectionTestUtils.setField(leg, "routeId", "1002");
        return leg;
    }

    @Test
    @DisplayName("#489 남은 초를 모르는 열차에 남은 역 수·도착 코드를 싣는다 — 역 도착 조회와 같은 계산")
    void candidatesCarryStationsAwayWhenSecondsUnknown() {
        properties.setCandidateCount(6);
        given(arrivalReader.arrivals("1002", "1002000222", 0)).willReturn(List.of(
                coded("2396", null, "0", "강남 진입"),
                coded("2398", null, "1", "강남 도착"),
                coded("2400", null, "3", "전역 출발"),
                coded("2402", null, "99", "[3]번째 전역 (역삼)"),
                coded("2404", 150, "99", "2분 30초 후"),
                coded("2406", null, "99", "이상한 문구")));

        List<SubwayTrainCandidate> candidates = service.candidates(USER, JOURNEY);

        assertThat(candidates).filteredOn(c -> c.kind() == SubwayTrainCandidate.Kind.UPCOMING)
                .extracting(SubwayTrainCandidate::trainNo, SubwayTrainCandidate::arrivalSeconds,
                        SubwayTrainCandidate::stationsAway, SubwayTrainCandidate::arrivalCode)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2396", null, 0, "0"),
                        org.assertj.core.groups.Tuple.tuple("2398", null, 0, "1"),
                        org.assertj.core.groups.Tuple.tuple("2400", null, 1, "3"),
                        org.assertj.core.groups.Tuple.tuple("2402", null, 3, "99"),
                        org.assertj.core.groups.Tuple.tuple("2404", 150, null, "99"),
                        org.assertj.core.groups.Tuple.tuple("2406", null, null, "99"));
    }

    @Test
    @DisplayName("#489 떠난 열차는 남은 역 수·도착 코드가 없다")
    void departedCandidatesHaveNoDistance() {
        given(arrivalReader.arrivals("1002", "1002000222", 0)).willReturn(List.of());
        given(arrivalReader.arrivalsByStation(anyString(), anyString(), anyString(), anyInt())).willReturn(List.of());
        given(trainLogPort.findDepartures(any(), any(), any(), any(), any(), any()))
                .willReturn(List.of(departure("2388")));

        assertThat(service.candidates(USER, JOURNEY))
                .isNotEmpty()
                .allSatisfy(c -> {
                    assertThat(c.kind()).isEqualTo(SubwayTrainCandidate.Kind.DEPARTED);
                    assertThat(c.stationsAway()).isNull();
                    assertThat(c.arrivalCode()).isNull();
                });
    }

    private static SubwayRealtimeArrivalReader.Arrival coded(String no, Integer sec, String code, String message) {
        return new SubwayRealtimeArrivalReader.Arrival(SubwayRealtimeTrain.builder().trainNo(no)
                .destinationName("성수").trainType("일반").arrivalCode(code).currentMessage(message)
                .receivedAt("2026-10-07 12:00:00").build(), sec);
    }

    private static SubwayRealtimeArrivalReader.Arrival arrival(String no, String dest, String type, Integer sec) {
        return new SubwayRealtimeArrivalReader.Arrival(SubwayRealtimeTrain.builder().trainNo(no)
                .destinationName(dest).trainType(type).receivedAt("2026-10-07 12:00:00").build(), sec);
    }

    @Test
    @DisplayName("고를 수 없는 대상: 대기 구간을 가리킨 legIndex / 이미 지난 탑승 / 사이에 도보 2개 / 사이에 버스")
    void rejectedTargets() {
        JourneyLeg walk = leg(2, JourneyLegType.WALK);
        JourneyLeg walk2 = leg(3, JourneyLegType.TRANSFER_WALK);
        JourneyLeg bus = leg(3, JourneyLegType.RIDE_BUS);
        JourneyLeg wait2 = leg(4, JourneyLegType.WAIT_SUBWAY);
        JourneyLeg ride2 = leg(5, JourneyLegType.RIDE_SUBWAY);

        // legIndex 가 대기를 가리킴
        assertNotWaiting(() -> service.candidates(USER, JOURNEY, 0));
        // 이미 지난 탑승
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY, 1))
                .willReturn(List.of(wait, ride, walk));
        session.moveToLeg(2);
        assertNotWaiting(() -> service.candidates(USER, JOURNEY, 1));
        // 앞 탑승과 다음 대기 사이 도보 2개
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY, 1))
                .willReturn(List.of(wait, ride, walk, walk2, wait2, ride2));
        session.moveToLeg(1);
        assertNotWaiting(() -> service.candidates(USER, JOURNEY, 5));
        // 사이에 버스
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY, 1))
                .willReturn(List.of(wait, ride, walk, bus, wait2, ride2));
        assertNotWaiting(() -> service.candidates(USER, JOURNEY, 5));
    }

    @Test
    @DisplayName("추적 가능: 명시 노선표 밖(인천1 → 1021)은 NO_REALTIME_LINE, 지하철 탑승이 아니면 409")
    void trackabilityEdges() {
        ReflectionTestUtils.setField(wait, "routeId", "1021");
        assertThat(service.trackability(USER, JOURNEY, 1).reason())
                .isEqualTo(watoo.grd.nextroute.application.journey.dto.SubwayTrackabilityView.Reason.NO_REALTIME_LINE);

        assertNotWaiting(() -> service.trackability(USER, JOURNEY, 0));
    }

    @Test
    @DisplayName("탑승 중 재선택은 이미 탄 열차로 넘긴다(aboard) — 링이 승차역으로 되감기지 않게")
    void ridingSelectionIsAboard() {
        session.moveToLeg(1);
        given(arrivalReader.justDepartedTrainNos("1002", "1002000222", 0)).willReturn(List.of("2386"));
        given(trainLogPort.findDepartures(any(), any(), any(), any(), any(), any())).willReturn(List.of());

        service.select(USER, JOURNEY, "2386", 1);

        verify(txService).bind(eq(ride), eq(new SubwayTrainSelectionTxService.ChosenTrain(
                "2386", LocalDate.parse("2026-10-07"), "1002", "내선", true)));
    }

    @Test
    @DisplayName("대기 바로 앞 도보(역까지 걷는 중·지하 환승 통로)에서도 다음 탑승 열차를 고른다")
    void walkRightBeforeTheWait() {
        JourneyLeg walk = leg(0, JourneyLegType.WALK);
        JourneyLeg wait1 = leg(1, JourneyLegType.WAIT_SUBWAY);
        JourneyLeg ride1 = leg(2, JourneyLegType.RIDE_SUBWAY);
        ReflectionTestUtils.setField(wait1, "selectedRouteOptionId", 99L);
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(JOURNEY, 1))
                .willReturn(List.of(walk, wait1, ride1));
        session.moveToLeg(0);

        assertThat(service.candidates(USER, JOURNEY, 2)).extracting(SubwayTrainCandidate::trainNo)
                .containsExactly("2390", "2392");
    }

    private static void assertNotWaiting(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.NOT_WAITING_FOR_SUBWAY);
    }

}
