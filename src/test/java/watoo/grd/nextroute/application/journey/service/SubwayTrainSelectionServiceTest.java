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
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.subway.config.SubwayExpressTrackingProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.application.subway.service.ServiceDateResolver;
import watoo.grd.nextroute.application.subway.service.SubwayExpressFixtures;
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
import java.util.Set;

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
    private final SubwayExpressTrackingProperties tracking = SubwayExpressFixtures.tracking(false);
    private final JourneySubwayStationChainReader chainReader = mock(JourneySubwayStationChainReader.class);
    private final SubwayRingStationResolver stationResolver = mock(SubwayRingStationResolver.class);
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
                Clock.fixed(NOW, ZoneOffset.UTC), SubwayExpressFixtures.judge(tracking), chainReader, stationResolver);
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
                "2394", LocalDate.parse("2026-10-07"), "1002", "내선", false,
                watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind.UNKNOWN, null, null)));
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
        given(arrivalReader.justDeparted("1002", "1002000222", 0)).willReturn(List.of(departedRow("2386")));

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
                "2229", LocalDate.parse("2026-10-06"), "1002", "내선", true,
                watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind.NORMAL, null, null)));
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
                "K4021", LocalDate.parse("2026-10-07"), "1002", "내선", true,
                watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind.NORMAL, null, null)));
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
                "2398", LocalDate.parse("2026-10-07"), "1002", "내선", false,
                watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind.NORMAL, null, null)));
        verify(arrivalReader, never()).arrivalsByStation(anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("곧 도착이던 열차가 고르는 사이 출발(arvlCd=2)했고 출발 기록은 아직 없어도 받는다(전엔 422)")
    void justDepartedBeforeDepartureLogIsSelectable() {
        given(trainLogPort.findDepartures(any(), any(), any(), any(), any(), any())).willReturn(List.of());
        given(arrivalReader.justDeparted("1002", "1002000222", 0)).willReturn(List.of(departedRow("2388")));

        service.select(USER, JOURNEY, "2388", 1);

        verify(txService).bind(eq(ride), eq(new SubwayTrainSelectionTxService.ChosenTrain(
                "2388", LocalDate.parse("2026-10-07"), "1002", "내선", true,
                watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind.NORMAL, null, null)));
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

    private static SubwayRealtimeArrivalReader.Arrival departedRow(String no) {
        return new SubwayRealtimeArrivalReader.Arrival(SubwayRealtimeTrain.builder().trainNo(no).destinationName("성수")
                .trainType("일반").arrivalCode("2").build(), null);
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
    @DisplayName("추적 가능: 지방 지하철(서울 노선 id 없음, null)은 500 이 아니라 NO_REALTIME_LINE — 앱이 열차 고르기를 열지 않게")
    void trackabilityRegionalNullRouteId() {
        ReflectionTestUtils.setField(wait, "routeId", null);
        assertThat(service.trackability(USER, JOURNEY, 1).reason())
                .isEqualTo(watoo.grd.nextroute.application.journey.dto.SubwayTrackabilityView.Reason.NO_REALTIME_LINE);
        assertThat(service.trackability(USER, JOURNEY, 1).trackable()).isFalse();
    }

    @Test
    @DisplayName("탑승 중 재선택은 이미 탄 열차로 넘긴다(aboard) — 링이 승차역으로 되감기지 않게")
    void ridingSelectionIsAboard() {
        session.moveToLeg(1);
        given(arrivalReader.justDeparted("1002", "1002000222", 0)).willReturn(List.of(departedRow("2386")));
        given(trainLogPort.findDepartures(any(), any(), any(), any(), any(), any())).willReturn(List.of());

        service.select(USER, JOURNEY, "2386", 1);

        verify(txService).bind(eq(ride), eq(new SubwayTrainSelectionTxService.ChosenTrain(
                "2386", LocalDate.parse("2026-10-07"), "1002", "내선", true,
                watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind.NORMAL, null, null)));
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


    // ── subway-express PR-S3: 열차 종류·정차·추적 가능 ─────────────────────────────────

    /** 9호선 상행(김포공항 → 중앙보훈병원). ODsay wayCode 1 = 상행. */
    private void line9(String board, String alight) {
        line("1009", board, 1, List.of(board, alight));
    }

    private void line(String lineId, String board, int wayCode, List<String> plannedNames) {
        String boardId = SubwayExpressFixtures.stationId(lineId, board);
        for (JourneyLeg leg : List.of(wait, ride)) {
            ReflectionTestUtils.setField(leg, "routeId", lineId);
            ReflectionTestUtils.setField(leg, "startStopId", boardId);
            ReflectionTestUtils.setField(leg, "startName", board);
        }
        JourneyLegRouteOption option = mock(JourneyLegRouteOption.class);
        given(option.getWayCode()).willReturn(wayCode);
        given(optionRepository.findById(99L)).willReturn(Optional.of(option));
        given(chainReader.stationNames(JOURNEY, 1, 1)).willReturn(plannedNames);
        List<SubwayRingChain.Station> chain = new java.util.ArrayList<>();
        for (int i = 0; i < plannedNames.size(); i++) {
            chain.add(new SubwayRingChain.Station(i, SubwayExpressFixtures.stationId(lineId, plannedNames.get(i)),
                    plannedNames.get(i)));
        }
        given(stationResolver.chainOf(lineId, plannedNames)).willReturn(new SubwayRingChain(chain));
    }

    private void cached(String lineId, String board, int wayCode, SubwayRealtimeArrivalReader.Arrival... arrivals) {
        given(arrivalReader.arrivals(lineId, SubwayExpressFixtures.stationId(lineId, board), wayCode))
                .willReturn(List.of(arrivals));
    }

    private static SubwayRealtimeArrivalReader.Arrival train(String lineId, String no, String dest, String type,
                                                             Integer sec, String... otherRowTypes) {
        return new SubwayRealtimeArrivalReader.Arrival(SubwayRealtimeTrain.builder().lineId(lineId).trainNo(no)
                .direction("상행").destinationName(dest).trainType(type).receivedAt("2026-10-07 12:00:00").build(),
                sec, Set.of(otherRowTypes));
    }

    private BusinessException rejected(String trainNo) {
        return (BusinessException) org.assertj.core.api.Assertions.catchThrowable(
                () -> service.select(USER, JOURNEY, trainNo, 1));
    }

    @Test
    @DisplayName("S3 후보: 9호선 가양→신논현 — 급행은 종류·정차·서비스를 싣고 플래그 꺼짐이면 고를 수 없음, 일반은 그대로")
    void line9CandidatesFlagOff() {
        line9("가양", "신논현");
        cached("1009", "가양", 1, train("1009", "9600", "중앙보훈병원", "급행", 60),
                train("1009", "9100", "중앙보훈병원", "일반", 120));

        List<SubwayTrainCandidate> candidates = service.candidates(USER, JOURNEY);

        SubwayTrainCandidate express = candidates.get(0);
        assertThat(express.trainNo()).isEqualTo("9600");
        assertThat(express.kind()).isEqualTo(SubwayTrainCandidate.Kind.UPCOMING);
        assertThat(express.express()).isTrue();
        assertThat(express.trainKind()).isEqualTo(TrainKind.EXPRESS);
        assertThat(express.trainLabel()).isEqualTo("급행");
        assertThat(express.expressServiceId()).isEqualTo("1009-express");
        assertThat(express.stopsAtBoarding()).isTrue();
        assertThat(express.stopsAtAlighting()).isTrue();
        assertThat(express.trackable()).as("플래그 꺼짐").isFalse();

        SubwayTrainCandidate normal = candidates.get(1);
        assertThat(normal.express()).isFalse();
        assertThat(normal.trainKind()).isEqualTo(TrainKind.NORMAL);
        assertThat(normal.trainLabel()).isEqualTo("일반");
        assertThat(normal.trackable()).isTrue();
        assertThat(normal.stopsAtBoarding()).isTrue();
        assertThat(normal.stopsAtAlighting()).isNull();
        assertThat(normal.expressServiceId()).isNull();
    }

    @Test
    @DisplayName("S3 선택: 플래그 꺼짐 → 급행 422 TRAIN_NOT_TRACKABLE(고정 안 함), 켜짐 → 고정. 일반은 플래그와 무관")
    void line9SelectFollowsFlag() {
        line9("가양", "신논현");
        cached("1009", "가양", 1, train("1009", "9600", "중앙보훈병원", "급행", 60),
                train("1009", "9100", "중앙보훈병원", "일반", 120));

        assertThat(rejected("9600").getErrorCode()).isEqualTo(JourneyErrorCode.TRAIN_NOT_TRACKABLE);
        verify(txService, never()).bind(any(), any());
        service.select(USER, JOURNEY, "9100", 1);

        tracking.setEnabled(true);
        assertThat(service.candidates(USER, JOURNEY).get(0).trackable()).isTrue();
        service.select(USER, JOURNEY, "9600", 1);
        verify(txService).bind(eq(ride), eq(new SubwayTrainSelectionTxService.ChosenTrain(
                "9600", LocalDate.parse("2026-10-07"), "1009", "상행", false,
                watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind.EXPRESS, "1009-express", "2026-10-21")));
        // 일반 열차는 종류만(subway-express PR-S4)
        verify(txService).bind(eq(ride), eq(new SubwayTrainSelectionTxService.ChosenTrain(
                "9100", LocalDate.parse("2026-10-07"), "1009", "상행", false,
                watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind.NORMAL, null, null)));
    }

    @Test
    @DisplayName("S3: 하차역(선유도)·탑승역(등촌)에 서지 않는 급행은 보이되 422 TRAIN_SKIPS_STATION — 플래그가 켜져 있어도")
    void line9SkipsStation() {
        tracking.setEnabled(true);
        line9("가양", "선유도");
        cached("1009", "가양", 1, train("1009", "9600", "중앙보훈병원", "급행", 60));

        SubwayTrainCandidate candidate = service.candidates(USER, JOURNEY).get(0);
        assertThat(candidate.stopsAtBoarding()).isTrue();
        assertThat(candidate.stopsAtAlighting()).isFalse();
        assertThat(candidate.trackable()).isFalse();
        assertThat(rejected("9600").getErrorCode()).isEqualTo(JourneyErrorCode.TRAIN_SKIPS_STATION);

        line9("등촌", "신논현");
        cached("1009", "등촌", 1, train("1009", "9600", "중앙보훈병원", "급행", 60));
        assertThat(service.candidates(USER, JOURNEY).get(0).stopsAtBoarding()).isFalse();
        assertThat(rejected("9600").getErrorCode()).isEqualTo(JourneyErrorCode.TRAIN_SKIPS_STATION);
        verify(txService, never()).bind(any(), any());
    }

    @Test
    @DisplayName("S3 종착 범위: 종합운동장행 급행으로 중앙보훈병원에 내리면 하차역 false → TRAIN_SKIPS_STATION")
    void terminalRange() {
        tracking.setEnabled(true);
        line9("가양", "중앙보훈병원");
        cached("1009", "가양", 1, train("1009", "9600", "종합운동장", "급행", 60));

        SubwayTrainCandidate candidate = service.candidates(USER, JOURNEY).get(0);
        assertThat(candidate.stopsAtAlighting()).isFalse();
        assertThat(rejected("9600").getErrorCode()).isEqualTo(JourneyErrorCode.TRAIN_SKIPS_STATION);
    }

    @Test
    @DisplayName("S3 열차 단위: 이 역 행은 '일반'이어도 같은 열차의 다른 역 행이 '급행'이면 급행")
    void perTrainAggregation() {
        tracking.setEnabled(true);
        line9("가양", "선유도");
        cached("1009", "가양", 1, train("1009", "9600", "중앙보훈병원", "일반", 60, "일반", "급행"));

        SubwayTrainCandidate candidate = service.candidates(USER, JOURNEY).get(0);
        assertThat(candidate.trainType()).isEqualTo("일반");
        assertThat(candidate.trainKind()).isEqualTo(TrainKind.EXPRESS);
        assertThat(candidate.express()).isTrue();
        assertThat(rejected("9600").getErrorCode()).isEqualTo(JourneyErrorCode.TRAIN_SKIPS_STATION);
    }

    @Test
    @DisplayName("S3 4호선 급행(4405)은 실시간 '일반'이어도 번호로 급행 — 사당→한대앞은 하차역 통과라 422")
    void line4ExpressByTrainNo() {
        tracking.setEnabled(true);
        line("1004", "사당", 2, List.of("사당", "한대앞"));
        cached("1004", "사당", 2, train("1004", "4405", "오이도", "일반", 60));

        SubwayTrainCandidate candidate = service.candidates(USER, JOURNEY).get(0);
        assertThat(candidate.trainKind()).isEqualTo(TrainKind.EXPRESS);
        assertThat(candidate.expressServiceId()).isEqualTo("1004-ansan");
        assertThat(candidate.stopsAtAlighting()).isFalse();
        assertThat(rejected("4405").getErrorCode()).isEqualTo(JourneyErrorCode.TRAIN_SKIPS_STATION);
    }

    @Test
    @DisplayName("S3 1호선 구로 급행(14xx)은 '급행' 표시여도 일반 — express=false, 고를 수 있다")
    void line1AllStopExpressIsNormal() {
        line("1001", "용산", 2, List.of("용산", "구로"));
        cached("1001", "용산", 2, train("1001", "1406", "구로", "급행", 60));

        SubwayTrainCandidate candidate = service.candidates(USER, JOURNEY).get(0);
        assertThat(candidate.express()).isFalse();
        assertThat(candidate.trainKind()).isEqualTo(TrainKind.NORMAL);
        assertThat(candidate.trackable()).isTrue();
        service.select(USER, JOURNEY, "1406", 1);
        verify(txService).bind(eq(ride), any());
    }

    @Test
    @DisplayName("S3 ITX 를 위치 기록(원시 directAt 9)으로만 알아도 떠난 열차에 ITX 로 보이고, 고르면 422 TRAIN_NOT_TRACKABLE")
    void itxKnownOnlyFromPosition() {
        tracking.setEnabled(true);
        line("1067", "청량리", 2, List.of("청량리", "가평"));
        Instant mid = NOW.minusSeconds(240);
        given(trainLogPort.findDepartures(any(), eq("1067"), eq("하행"), any(), any(), any())).willReturn(List.of(
                new watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort.TrainDeparture("2029",
                        new SubwayTrainEvent(SubwayTrainEvent.Type.DEPARTED, "x", "청량리", mid.minusSeconds(30),
                                mid.plusSeconds(30), SubwayTrainEvent.Quality.CORROBORATED, "1067080130",
                                SubwayExpressType.UNKNOWN, "9"))));

        SubwayTrainCandidate candidate = service.candidates(USER, JOURNEY).get(0);
        assertThat(candidate.kind()).isEqualTo(SubwayTrainCandidate.Kind.DEPARTED);
        assertThat(candidate.trainKind()).isEqualTo(TrainKind.ITX);
        assertThat(candidate.trainLabel()).isEqualTo("ITX-청춘");
        assertThat(candidate.trainType()).isEqualTo("ITX");
        assertThat(candidate.express()).isFalse();
        assertThat(candidate.trackable()).isFalse();
        assertThat(rejected("2029").getErrorCode()).isEqualTo(JourneyErrorCode.TRAIN_NOT_TRACKABLE);
        verify(txService, never()).bind(any(), any());
    }

    @Test
    @DisplayName("S3 떠난 열차 후보: 출발 기록의 행선지·종류를 싣는다(예전엔 express=false·trainType=null 고정)")
    void departedCandidateCarriesSourceFields() {
        session.moveToLeg(1);
        given(trainLogPort.findDepartures(any(), any(), any(), any(), any(), any())).willReturn(List.of());
        given(arrivalDepartureLog.find(any(), eq("1002"), eq("내선"), eq("1002000222"), any(), any())).willReturn(
                List.of(new watoo.grd.nextroute.application.subway.port.out.SubwayArrivalDepartureLogPort.Row(
                        "2398", "성수", "일반", NOW.minusSeconds(120))));
        given(arrivalReader.justDeparted("1002", "1002000222", 0)).willReturn(List.of(
                new SubwayRealtimeArrivalReader.Arrival(SubwayRealtimeTrain.builder().trainNo("2396")
                        .destinationName("성수").trainType("일반").build(), null)));

        List<SubwayTrainCandidate> candidates = service.candidates(USER, JOURNEY);

        assertThat(candidates).extracting(SubwayTrainCandidate::trainNo).containsExactly("2396", "2398");
        assertThat(candidates).extracting(SubwayTrainCandidate::destination).containsExactly("성수", "성수");
        assertThat(candidates).extracting(SubwayTrainCandidate::trainType).containsExactly("일반", "일반");
        assertThat(candidates).extracting(SubwayTrainCandidate::trainKind)
                .containsExactly(TrainKind.NORMAL, TrainKind.NORMAL);
        assertThat(candidates).allMatch(SubwayTrainCandidate::trackable);
    }

    @Test
    @DisplayName("S3 급행이 없으면 하차역(계획 체인 재쪼개기)을 읽지 않는다")
    void noExpressNoChainRead() {
        service.candidates(USER, JOURNEY);

        org.mockito.Mockito.verifyNoInteractions(chainReader, stationResolver);
    }
}
