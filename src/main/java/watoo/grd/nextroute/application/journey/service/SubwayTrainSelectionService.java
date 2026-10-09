package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneySubwaySelectionProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;
import watoo.grd.nextroute.application.journey.dto.SubwayTrainCandidate;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.application.subway.service.SubwayDepartureLookup;
import watoo.grd.nextroute.application.subway.service.ServiceDateResolver;
import watoo.grd.nextroute.application.subway.service.SubwayDirectionNormalizer;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 역에서 탈 열차를 사용자가 고른다(#265). <b>일반 여정에도 적용된다.</b>
 *
 * <pre>
 * 1. 지금 단계가 지하철 대기이고 다음이 지하철 탑승인지     ← 아니면 409 NOT_WAITING_FOR_SUBWAY
 * 2. 그 역·방향 실시간 도착(역별 호출), 없으면 10분 안 출발 로그 ← 트랜잭션 밖
 * 3. 고른 열차가 목록에 있으면 고정 행을 CONFIRMED·USER_SELECTED 로   ← 트랜잭션 안(SubwayTrainSelectionTxService)
 * </pre>
 *
 * <p>고르지 않고 이동이 시작되면 기존 자동 판정(M7)이 그대로 돈다. 고른 뒤 틀렸으면 기존 "이 열차 아님"(reset) 통로.
 * 방향은 대기 단계 노선 후보의 {@code wayCode} 다 — 모르면 후보를 내지 않는다(반대 방향 열차를 집는다).
 */
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class SubwayTrainSelectionService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 이 역을 이만큼 안에 떠난 열차도 고를 수 있다 — 역 도착 응답의 "방금 떠난 열차"(#299·#301 {@code DEPARTED_WINDOW})와
     * 같은 창이다. 사용자는 대개 타고 나서 앱을 연다.
     */
    static final Duration DEPARTED_WINDOW = Duration.ofMinutes(10);
    private static final DateTimeFormatter RECEIVED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JourneySubwaySelectionProperties properties;
    private final JourneySessionRepository sessionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final SubwayRealtimeArrivalReader arrivalReader;
    private final ServiceDateResolver serviceDateResolver;
    private final SubwayDepartureLookup departureLookup;
    private final SubwayTrainSelectionTxService txService;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    /** 그 역·방향 다음 열차 최대 {@code candidateCount} 대(도착 순). 방향을 모르면 빈 목록. */
    public List<SubwayTrainCandidate> candidates(Long userId, Long journeyId) {
        requireEnabled();
        Waiting waiting = waiting(userId, journeyId);
        return arrivals(waiting).stream()
                .limit(properties.getCandidateCount())
                .map(SubwayTrainSelectionService::toCandidate)
                .toList();
    }

    /**
     * @param routeRevision 앱이 보고 있는 개정본. 다르면 409 {@code STALE_ROUTE_REVISION}
     * @throws BusinessException 422 {@code TRAIN_NOT_IN_CANDIDATES} — 지금 그 역·방향 도착 목록에 없는 열차
     */
    public SubwayBindingView select(Long userId, Long journeyId, String trainNo, int routeRevision) {
        requireEnabled();
        Waiting waiting = waiting(userId, journeyId);
        if (waiting.session().getRouteRevision() != routeRevision) {
            record("stale");
            throw new StaleRouteRevisionException(waiting.session().getRouteRevision());
        }
        // 후보 수가 아니라 지금 도착 목록 전체에서 찾는다 — 앞차가 막 들어와 순위가 밀린 열차를 거절하지 않게.
        Optional<SubwayRealtimeArrivalReader.Arrival> chosen = arrivals(waiting).stream()
                .filter(arrival -> trainNo.equals(arrival.train().getTrainNo()))
                .findFirst();
        String lineId = waiting.waitLeg().getRouteId();
        String direction = SubwayDirectionNormalizer.fromWayCode(lineId, waiting.wayCode());
        LocalDate serviceDate = serviceDateResolver.resolve(clock.instant());
        boolean departed = false;
        if (chosen.isEmpty()) {
            // 곧 오는 목록에 없으면 이 역을 방금 떠난 열차인지 본다(#299 의 departed 를 고른 경우). 그 열차의 운행일은
            // 출발 사건의 운행일이다 — 04:00 경계를 걸치면 지금 운행일과 다르다.
            Optional<LocalDate> departedOn = departedServiceDate(waiting, lineId, direction, trainNo);
            if (departedOn.isEmpty()) {
                record("not-in-candidates");
                throw new BusinessException(JourneyErrorCode.TRAIN_NOT_IN_CANDIDATES);
            }
            serviceDate = departedOn.get();
            departed = true;
        }
        SubwayTrainSelectionTxService.ChosenTrain train = new SubwayTrainSelectionTxService.ChosenTrain(
                trainNo, serviceDate, lineId, direction);
        try {
            SubwayBindingView view = txService.bind(waiting.ride(), train);
            record(departed ? "selected-departed" : "selected");
            return view;
        } catch (DataIntegrityViolationException e) {
            // 같은 순간 이동 관측이 행을 먼저 만들었다(단계당 행 하나, V44). 재시도하면 그 행 위에 얹힌다.
            record("busy");
            throw new BusinessException(JourneyErrorCode.SUBWAY_BINDING_BUSY);
        }
    }

    /**
     * 그 열차가 이 역·방향을 {@link #DEPARTED_WINDOW} 안에 떠났으면 그 출발의 운행일. 원천은 역 도착 응답의 "떠난 열차"와
     * 같은 창구({@link SubwayDepartureLookup})다 — 화면에 보인 떠난 열차는 위치 API 가 없는 노선이어도 고를 수 있다(#317).
     * 못 읽으면 빈 값(= 422, 앱은 목록을 다시 받는다).
     */
    private Optional<LocalDate> departedServiceDate(Waiting waiting, String lineId, String direction, String trainNo) {
        String stationId = waiting.waitLeg().getStartStopId();
        if (lineId == null || direction == null || stationId == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        try {
            return departureLookup.find(lineId, direction, stationId, now.minus(DEPARTED_WINDOW), now).stream()
                    .filter(d -> trainNo.equals(d.trainNo()))
                    .map(SubwayDepartureLookup.Departure::serviceDate)
                    .findFirst();
        } catch (RuntimeException e) {
            log.warn("[SubwaySelection] 떠난 열차 조회 실패 line={} station={}: {}", lineId, stationId, e.getMessage());
            return Optional.empty();
        }
    }

    private List<SubwayRealtimeArrivalReader.Arrival> arrivals(Waiting waiting) {
        if (waiting.wayCode() == null || waiting.waitLeg().getRouteId() == null
                || waiting.waitLeg().getStartStopId() == null) {
            return List.of();
        }
        return arrivalReader.arrivalsByStation(waiting.waitLeg().getRouteId(), waiting.waitLeg().getStartStopId(),
                waiting.waitLeg().getStartName(), waiting.wayCode());
    }

    private Waiting waiting(Long userId, Long journeyId) {
        JourneySession session = sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        if (session.getStatus() != JourneyStatus.ACTIVE) {
            throw new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE);
        }
        List<JourneyLeg> legs = legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                journeyId, session.getRouteRevision());
        JourneyLeg wait = legAt(legs, session.getCurrentLegIndex());
        JourneyLeg ride = legAt(legs, session.getCurrentLegIndex() + 1);
        if (wait == null || wait.getType() != JourneyLegType.WAIT_SUBWAY
                || ride == null || ride.getType() != JourneyLegType.RIDE_SUBWAY) {
            record("not-waiting");
            throw new BusinessException(JourneyErrorCode.NOT_WAITING_FOR_SUBWAY);
        }
        Integer wayCode = Optional.ofNullable(wait.getSelectedRouteOptionId())
                .flatMap(routeOptionRepository::findById)
                .map(JourneyLegRouteOption::getWayCode)
                .orElse(null);
        return new Waiting(session, wait, ride, wayCode);
    }

    private static JourneyLeg legAt(List<JourneyLeg> legs, int index) {
        return legs.stream().filter(leg -> leg.getLegIndex() == index).findFirst().orElse(null);
    }

    private static SubwayTrainCandidate toCandidate(SubwayRealtimeArrivalReader.Arrival arrival) {
        String type = arrival.train().getTrainType();
        boolean express = type != null && type.contains("급행");
        return new SubwayTrainCandidate(arrival.train().getTrainNo(), arrival.train().getDestinationName(),
                express, type, arrival.arrivalSeconds(), observedAt(arrival.train().getReceivedAt()));
    }

    private static java.time.Instant observedAt(String receivedAt) {
        if (receivedAt == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(receivedAt, RECEIVED_AT).atZone(SEOUL).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
    }

    private void record(String result) {
        meterRegistry.counter("subway.selection", "result", result).increment();
    }

    private record Waiting(JourneySession session, JourneyLeg waitLeg, JourneyLeg ride, Integer wayCode) {
    }
}
