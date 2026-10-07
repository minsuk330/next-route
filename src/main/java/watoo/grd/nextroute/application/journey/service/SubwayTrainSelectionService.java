package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneySubwaySelectionProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;
import watoo.grd.nextroute.application.journey.dto.SubwayTrainCandidate;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
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
 * 2. 그 역·방향 실시간 도착(역별 호출)                    ← 트랜잭션 밖
 * 3. 고른 열차가 목록에 있으면 고정 행을 CONFIRMED·USER_SELECTED 로   ← 트랜잭션 안(SubwayTrainSelectionTxService)
 * </pre>
 *
 * <p>고르지 않고 이동이 시작되면 기존 자동 판정(M7)이 그대로 돈다. 고른 뒤 틀렸으면 기존 "이 열차 아님"(reset) 통로.
 * 방향은 대기 단계 노선 후보의 {@code wayCode} 다 — 모르면 후보를 내지 않는다(반대 방향 열차를 집는다).
 */
@Service
@RequiredArgsConstructor
public class SubwayTrainSelectionService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter RECEIVED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JourneySubwaySelectionProperties properties;
    private final JourneySessionRepository sessionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final SubwayRealtimeArrivalReader arrivalReader;
    private final ServiceDateResolver serviceDateResolver;
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
        if (chosen.isEmpty()) {
            record("not-in-candidates");
            throw new BusinessException(JourneyErrorCode.TRAIN_NOT_IN_CANDIDATES);
        }
        String lineId = waiting.waitLeg().getRouteId();
        SubwayTrainSelectionTxService.ChosenTrain train = new SubwayTrainSelectionTxService.ChosenTrain(
                trainNo, serviceDateResolver.resolve(clock.instant()), lineId,
                SubwayDirectionNormalizer.fromWayCode(lineId, waiting.wayCode()));
        try {
            SubwayBindingView view = txService.bind(waiting.ride(), train);
            record("selected");
            return view;
        } catch (DataIntegrityViolationException e) {
            // 같은 순간 이동 관측이 행을 먼저 만들었다(단계당 행 하나, V44). 재시도하면 그 행 위에 얹힌다.
            record("busy");
            throw new BusinessException(JourneyErrorCode.SUBWAY_BINDING_BUSY);
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
