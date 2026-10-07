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
                arrivalReader, serviceDateResolver, trainLogPort, txService, new SimpleMeterRegistry(),
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
    @DisplayName("대기 단계가 아니면 409 NOT_WAITING_FOR_SUBWAY")
    void notWaiting() {
        session.moveToLeg(1);

        assertThatThrownBy(() -> service.candidates(USER, JOURNEY))
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.NOT_WAITING_FOR_SUBWAY);
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
                "2229", LocalDate.parse("2026-10-06"), "1002", "내선")));
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

    private static SubwayRealtimeArrivalReader.Arrival arrival(String no, String dest, String type, Integer sec) {
        return new SubwayRealtimeArrivalReader.Arrival(SubwayRealtimeTrain.builder().trainNo(no)
                .destinationName(dest).trainType(type).receivedAt("2026-10-07 12:00:00").build(), sec);
    }
}
