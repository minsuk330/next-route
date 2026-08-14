package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneySubwayBindingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingDecision;
import watoo.grd.nextroute.application.journey.observability.SubwayBindingMetrics;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.ServiceDateResolver;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingConfidence;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEvent;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEventType;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingSource;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayBindingEventRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 재료를 모으는 쪽의 계약.
 *
 * <p>판정 규칙이 아무리 맞아도 <b>입력이 조용히 비면</b> 아무 일도 일어나지 않는다. 여기서 고정하는
 * 것은 그 조용한 실패들이다 — 중간점 색인 때문에 후보가 안 잡히는 것, 04:00 을 자정으로 착각해
 * 심야에 후보가 통째로 비는 것, 거부된 세대의 후보를 다시 읽는 것.
 */
class SubwayBindingMatchServiceTest {

    private static final long LEG_ID = 101L;
    private static final long JOURNEY_ID = 7L;
    private static final int REVISION = 1;
    private static final int LEG_INDEX = 3;
    private static final long OPTION_ID = 55L;
    private static final String LINE = "1001";
    private static final String BOARD_STATION = "1001000133";
    private static final Instant NOW = Instant.parse("2026-08-14T09:00:00Z");

    private SubwayLegBindingRepository bindingRepository;
    private SubwayBindingEventRepository eventRepository;
    private JourneyLegRepository legRepository;
    private JourneyLegRouteOptionRepository routeOptionRepository;
    private JourneySubwayStationChainReader stationChainReader;
    private SubwayTrainLogPort trainLogPort;
    private SubwayTrainMatcher matcher;
    private SubwayBindingMatchTxService txService;
    private JourneySubwayBindingProperties properties;
    private SubwayBindingMatchService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        bindingRepository = mock(SubwayLegBindingRepository.class);
        eventRepository = mock(SubwayBindingEventRepository.class);
        legRepository = mock(JourneyLegRepository.class);
        routeOptionRepository = mock(JourneyLegRouteOptionRepository.class);
        stationChainReader = mock(JourneySubwayStationChainReader.class);
        trainLogPort = mock(SubwayTrainLogPort.class);
        matcher = mock(SubwayTrainMatcher.class);
        txService = mock(SubwayBindingMatchTxService.class);

        properties = new JourneySubwayBindingProperties();
        properties.setDepartureLookback(Duration.ofMinutes(5));
        properties.setIndexHalfWidthGuard(Duration.ofMinutes(3));
        properties.setArrivalLookaround(Duration.ofMinutes(4));

        service = new SubwayBindingMatchService(bindingRepository, eventRepository, legRepository,
                routeOptionRepository, stationChainReader, trainLogPort, new ServiceDateResolver(),
                matcher, txService, new SubwayBindingMetrics(new SimpleMeterRegistry()),
                properties, Clock.fixed(NOW, ZoneOffset.UTC));

        when(matcher.match(any())).thenReturn(SubwayBindingDecision.none("no-departure"));
        when(legRepository.findById(LEG_ID)).thenReturn(Optional.of(rideLeg()));
        when(routeOptionRepository.findById(OPTION_ID)).thenReturn(Optional.of(routeOption(1)));
        when(eventRepository.findByJourneyLegIdOrderByIdDesc(eq(LEG_ID), any(Limit.class)))
                .thenReturn(List.of());
    }

    @Test
    @DisplayName("조회창을 색인 반폭만큼 넓게 긁는다 — 색인 점수가 사건 구간의 중간점이라서다")
    void widensTheQueryWindowByTheIndexGuard() {
        Instant movedAt = NOW.minusSeconds(60);
        givenScanned(pendingBinding(), movementRow(movedAt, 1));

        service.runOnce();

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(trainLogPort).findDepartures(any(LocalDate.class), eq(LINE), eq("상행"),
                eq(BOARD_STATION), from.capture(), to.capture());
        assertThat(from.getValue()).isEqualTo(movedAt.minusSeconds(300 + 180));
        assertThat(to.getValue()).isEqualTo(movedAt.plusSeconds(300 + 180));
    }

    @Test
    @DisplayName("넓게 긁은 뒤 실제로 겹치지 않는 사건은 코드가 거른다")
    void dropsEventsThatDoNotActuallyOverlap() {
        Instant movedAt = NOW.minusSeconds(60);
        givenScanned(pendingBinding(), movementRow(movedAt, 1));
        when(trainLogPort.findDepartures(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(
                        departure("1001", movedAt.minusSeconds(5), movedAt.plusSeconds(5)),
                        departure("9999", movedAt.plusSeconds(400), movedAt.plusSeconds(420))));

        service.runOnce();

        assertThat(captureInput().departures()).hasSize(1);
        assertThat(captureInput().departures().get(0).trainNo()).isEqualTo("1001");
    }

    @Test
    @DisplayName("구간이 04:00 을 걸치면 두 영업일을 모두 조회한다 — 자정으로 착각하면 심야가 통째로 빈다")
    void queriesBothServiceDatesAcrossTheBoundary() {
        // 2026-08-15 03:58 KST. 앞뒤 5분이면 04:00 선을 넘는다.
        Instant movedAt = Instant.parse("2026-08-14T18:58:00Z");
        givenScanned(pendingBinding(), movementRow(movedAt, 1));

        service.runOnce();

        ArgumentCaptor<LocalDate> dates = ArgumentCaptor.forClass(LocalDate.class);
        verify(trainLogPort, org.mockito.Mockito.times(2)).findDepartures(dates.capture(),
                any(), any(), any(), any(), any());
        assertThat(dates.getAllValues())
                .containsExactly(LocalDate.of(2026, 8, 14), LocalDate.of(2026, 8, 15));
    }

    @Test
    @DisplayName("후보 복구는 현재 version·보정 세대와 일치하는 감사행만 읽는다")
    void recoversCandidatesOnlyFromTheMatchingGeneration() {
        SubwayLegBinding binding = provisionalBinding(4, 1);
        givenScanned(binding,
                decisionRow(SubwayBindingEventType.PROVISIONAL, 4, 0, "1111"),
                decisionRow(SubwayBindingEventType.PROVISIONAL, 3, 1, "2222"),
                decisionRow(SubwayBindingEventType.PROVISIONAL, 4, 1, "3333"));

        service.runOnce();

        assertThat(captureInput().knownCandidates()).hasSize(1);
        assertThat(captureInput().knownCandidates().get(0).trainNo()).isEqualTo("3333");
    }

    @Test
    @DisplayName("사용자가 거부한 열차를 판정 입력에 실어 준다 — 없으면 그 열차를 그대로 다시 고른다")
    void carriesRejectedTrainsIntoTheDecision() {
        givenScanned(pendingBinding(), userResetRow("7777"));

        assertThat(captureInputAfterRun().rejected())
                .containsExactly(new TrainIdentity(LocalDate.of(2026, 8, 14), LINE, "상행", "7777"));
    }

    @Test
    @DisplayName("잠정 판정 뒤에는 출발을 다시 긁지 않는다 — 그 단계에서 필요한 것은 도착 로그다")
    void doesNotRequeryDeparturesWhileConfirming() {
        givenScanned(provisionalBinding(4, 0),
                decisionRow(SubwayBindingEventType.PROVISIONAL, 4, 0, "1111"));

        service.runOnce();

        verify(trainLogPort, never()).findDepartures(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("판정이 아무것도 바꾸지 않으면 쓰기 트랜잭션을 열지 않는다")
    void doesNotOpenAWriteTransactionForNoDecision() {
        givenScanned(pendingBinding());

        service.runOnce();

        verify(txService, never()).apply(any(), any());
    }

    @Test
    @DisplayName("한 건이 터져도 나머지는 계속 처리한다")
    void isolatesFailuresPerRow() {
        SubwayLegBinding broken = pendingBinding();
        ReflectionTestUtils.setField(broken, "journeyLegId", 999L);
        when(legRepository.findById(999L)).thenThrow(new IllegalStateException("boom"));
        when(bindingRepository.findMatchable(anyInt()))
                .thenReturn(List.of(broken, pendingBinding()));

        assertThat(service.runOnce()).isEqualTo(2);
        verify(matcher).match(any());
    }

    // ── 재료 ────────────────────────────────────────────────────────────────

    private void givenScanned(SubwayLegBinding binding, SubwayBindingEvent... rows) {
        when(bindingRepository.findMatchable(anyInt())).thenReturn(List.of(binding));
        when(eventRepository.findByJourneyLegIdOrderByIdDesc(eq(LEG_ID), any(Limit.class)))
                .thenReturn(List.of(rows));
    }

    private SubwayTrainMatcher.MatchInput captureInputAfterRun() {
        service.runOnce();
        return captureInput();
    }

    private SubwayTrainMatcher.MatchInput captureInput() {
        ArgumentCaptor<SubwayTrainMatcher.MatchInput> captor =
                ArgumentCaptor.forClass(SubwayTrainMatcher.MatchInput.class);
        verify(matcher, org.mockito.Mockito.atLeastOnce()).match(captor.capture());
        return captor.getValue();
    }

    private SubwayLegBinding pendingBinding() {
        SubwayLegBinding binding = SubwayLegBinding.pending(LEG_ID, JOURNEY_ID, REVISION, LEG_INDEX,
                NOW.minusSeconds(60), SubwayBindingSource.MOTION_AUTOMOTIVE,
                SubwayBindingConfidence.HIGH);
        ReflectionTestUtils.setField(binding, "lastMovementSeq", 1);
        return binding;
    }

    private SubwayLegBinding provisionalBinding(int version, int resetGeneration) {
        SubwayLegBinding binding = pendingBinding();
        ReflectionTestUtils.setField(binding, "status", SubwayBindingStatus.PROVISIONAL);
        ReflectionTestUtils.setField(binding, "version", version);
        ReflectionTestUtils.setField(binding, "resetGeneration", resetGeneration);
        return binding;
    }

    private JourneyLeg rideLeg() {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(REVISION)
                .legIndex(LEG_INDEX)
                .type(JourneyLegType.RIDE_SUBWAY)
                .state(JourneyLegState.ACTIVE)
                .startStopId(BOARD_STATION)
                .endStopId("1001000135")
                .build();
        ReflectionTestUtils.setField(leg, "id", LEG_ID);
        leg.selectRouteOption(OPTION_ID, LINE);
        return leg;
    }

    private JourneyLegRouteOption routeOption(int wayCode) {
        return JourneyLegRouteOption.builder()
                .journeyLegId(LEG_ID - 1)
                .optionIndex(0)
                .laneIndex(0)
                .routeId(LINE)
                .wayCode(wayCode)
                .build();
    }

    private SubwayTrainLogPort.TrainDeparture departure(String trainNo, Instant from, Instant to) {
        return new SubwayTrainLogPort.TrainDeparture(trainNo,
                new SubwayTrainEvent(SubwayTrainEvent.Type.DEPARTED, BOARD_STATION, "서울역",
                        from, to, SubwayTrainEvent.Quality.OBSERVED, null,
                        SubwayExpressType.NORMAL));
    }

    private SubwayBindingEvent movementRow(Instant movedAt, int sequence) {
        return event(SubwayBindingEventType.MOVEMENT_START, sequence, 0, null, null,
                "{\"movementStartedAt\":\"" + movedAt + "\"}");
    }

    private SubwayBindingEvent decisionRow(SubwayBindingEventType type, int bindingVersion,
                                           int resetGeneration, String trainNo) {
        String detail = """
                {"reason":"t0-match","candidates":[{"trainNo":"%s","serviceDate":"2026-08-14",
                "lineId":"1001","direction":"상행","departureFrom":"2026-08-14T08:58:00Z",
                "departureTo":"2026-08-14T08:58:10Z","quality":"OBSERVED","express":"NORMAL",
                "gapSeconds":0}]}""".formatted(trainNo).replace("\n", "");
        return event(type, null, resetGeneration, bindingVersion, null, detail);
    }

    private SubwayBindingEvent userResetRow(String trainNo) {
        return event(SubwayBindingEventType.USER_RESET, null, 0, null, trainNo, "{}");
    }

    private SubwayBindingEvent event(SubwayBindingEventType type, Integer sequence,
                                     int resetGeneration, Integer bindingVersion,
                                     String chosenTrainNo, String detail) {
        try {
            return SubwayBindingEvent.builder()
                    .journeyLegId(LEG_ID)
                    .journeyId(JOURNEY_ID)
                    .routeRevision(REVISION)
                    .legIndex(LEG_INDEX)
                    .eventType(type)
                    .evidenceSequence(sequence)
                    .bindingVersion(bindingVersion)
                    .resetGeneration(resetGeneration)
                    .occurredAt(NOW.minusSeconds(60))
                    .receivedAt(NOW.minusSeconds(60))
                    .chosenTrainNo(chosenTrainNo)
                    .chosenServiceDate(chosenTrainNo == null ? null : LocalDate.of(2026, 8, 14))
                    .chosenLineId(chosenTrainNo == null ? null : LINE)
                    .chosenDirection(chosenTrainNo == null ? null : "상행")
                    .detail(objectMapper.readTree(detail))
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
