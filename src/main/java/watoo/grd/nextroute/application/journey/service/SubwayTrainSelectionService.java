package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneySubwaySelectionProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;
import watoo.grd.nextroute.application.journey.dto.SubwayTrackabilityView;
import watoo.grd.nextroute.application.journey.dto.SubwayTrainCandidate;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.application.subway.service.SubwayDepartureLookup;
import watoo.grd.nextroute.application.subway.service.ServiceDateResolver;
import watoo.grd.nextroute.application.subway.service.SubwayDirectionNormalizer;
import watoo.grd.nextroute.application.subway.service.SubwayLineIdMapper;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 역에서 탈 열차를 사용자가 고른다(#265). <b>일반 여정에도 적용된다.</b>
 *
 * <pre>
 * 1. 고를 수 있는 단계인지(아래 세 경우)                     ← 아니면 409 NOT_WAITING_FOR_SUBWAY
 * 2. 그 역·방향 실시간 도착(역별 호출), 없으면 10분 안 출발 로그 ← 트랜잭션 밖
 * 3. 고른 열차가 목록에 있으면 고정 행을 CONFIRMED·USER_SELECTED 로   ← 트랜잭션 안(SubwayTrainSelectionTxService)
 * </pre>
 *
 * <p><b>고를 수 있는 단계</b>(Dynamic Island 정상화, 사용자 결정 2026-10-10 — 열차 연결은 선택만, 고르면 즉시 탑승):
 * <pre>
 * WAITING  지금 = 대상 탑승의 바로 앞 대기         곧 올 열차 + 막 떠난 열차
 * RIDING   지금 = 대상 탑승 (다른 열차예요)       떠난 열차만 — 승차역 도착 목록은 뒤차다
 * TRANSFER 지금 = 앞 지하철 탑승, 사이는 도보 0~1개 곧 올 열차 + 막 떠난 열차 — 지하 환승은 GPS 가 없어 앱이 하차를
 *                                                  알 수 없다. 다음 열차를 고르는 것이 하차 신호다
 * </pre>
 * 고른 뒤 틀렸으면 탑승 중에 다시 고른다. 방향은 대기 단계 노선 후보의 {@code wayCode} 다 — 모르면 후보를 내지 않는다
 * (반대 방향 열차를 집는다).
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

    /** 떠난 열차 후보 상한 — 오래 탄 사람에게 한 시간치 출발을 다 보여 주지 않는다. */
    static final int DEPARTED_CANDIDATE_LIMIT = 6;

    /** 탑승 중 재선택의 떠난 열차 창 여유 — 탑승 전환이 승강장에서 조금 늦게 들어와도 그 열차가 창 안에 남게. */
    static final Duration RIDING_WINDOW_SLACK = Duration.ofMinutes(2);

    public List<SubwayTrainCandidate> candidates(Long userId, Long journeyId) {
        return candidates(userId, journeyId, null);
    }

    /**
     * 고를 수 있는 열차. 곧 올 열차는 최대 {@code candidateCount} 대(도착 순), 떠난 열차는 최근 순. 방향을 모르면 빈 목록.
     *
     * @param rideLegIndex 대상 탑승 구간. null 이면 지금 단계 기준
     */
    public List<SubwayTrainCandidate> candidates(Long userId, Long journeyId, Integer rideLegIndex) {
        requireEnabled();
        Waiting waiting = waiting(userId, journeyId, rideLegIndex);
        List<SubwayTrainCandidate> result = new ArrayList<>();
        if (waiting.phase() != Phase.RIDING) {
            List<SubwayRealtimeArrivalReader.Arrival> arrivals = cachedArrivals(waiting);
            if (arrivals.isEmpty()) {
                arrivals = liveArrivals(waiting);
            }
            arrivals.stream()
                    .limit(properties.getCandidateCount())
                    .map(SubwayTrainSelectionService::toCandidate)
                    .forEach(result::add);
        }
        Set<String> seen = new LinkedHashSet<>(result.stream().map(SubwayTrainCandidate::trainNo).toList());
        departedCandidates(waiting).stream()
                .filter(candidate -> seen.add(candidate.trainNo()))
                .forEach(result::add);
        return result;
    }

    /**
     * 이 구간을 실시간으로 추적할 수 있는지. 단계 제약은 두지 않는다 — 앱은 여정을 시작하며 구간마다 미리 묻는다.
     *
     * @param rideLegIndex 지하철 탑승 구간. null 이면 지금 단계 기준
     */
    public SubwayTrackabilityView trackability(Long userId, Long journeyId, Integer rideLegIndex) {
        requireEnabled();
        JourneySession session = activeSession(userId, journeyId);
        List<JourneyLeg> legs = legsOf(session);
        int rideIndex = rideLegIndex != null ? rideLegIndex : defaultRideIndex(legs, session.getCurrentLegIndex());
        JourneyLeg ride = legAt(legs, rideIndex);
        JourneyLeg wait = legAt(legs, rideIndex - 1);
        if (!isSubwayPair(wait, ride)) {
            throw new BusinessException(JourneyErrorCode.NOT_WAITING_FOR_SUBWAY);
        }
        if (SubwayLineIdMapper.toLineName(wait.getRouteId()).isEmpty()) {
            return SubwayTrackabilityView.no(rideIndex, SubwayTrackabilityView.Reason.NO_REALTIME_LINE);
        }
        if (wait.getStartStopId() == null) {
            return SubwayTrackabilityView.no(rideIndex, SubwayTrackabilityView.Reason.NO_STATION);
        }
        if (SubwayDirectionNormalizer.fromWayCode(wait.getRouteId(), wayCodeOf(wait)) == null) {
            return SubwayTrackabilityView.no(rideIndex, SubwayTrackabilityView.Reason.UNKNOWN_DIRECTION);
        }
        return SubwayTrackabilityView.yes(rideIndex);
    }

    /**
     * 역 도착 노선도(#317)에 보인 열차는 고를 수 있어야 한다. 그래서 노선도와 <b>같은 원천을 먼저</b> 본다.
     *
     * <pre>
     * 1. 수집기 캐시의 곧 오는 열차   ← 노선도와 같은 원천
     * 2. 역별 실시간 호출             ← 수집기가 꺼졌을 때
     * 3. 캐시의 막 떠난 열차(arvlCd=2) ← 화면에서 "곧 도착"이던 열차가 고르는 사이 출발한 틈
     * 4. 10분 안 출발 기록            ← 노선도의 떠난 열차와 같은 창구
     * </pre>
     *
     * @param routeRevision 앱이 보고 있는 개정본. 다르면 409 {@code STALE_ROUTE_REVISION}
     * @throws BusinessException 422 {@code TRAIN_NOT_IN_CANDIDATES} — 네 원천 어디에도 없는 열차
     */
    public SubwayBindingView select(Long userId, Long journeyId, String trainNo, int routeRevision) {
        return select(userId, journeyId, trainNo, routeRevision, null);
    }

    /**
     * @param rideLegIndex 대상 탑승 구간. null 이면 지금 단계 기준
     */
    public SubwayBindingView select(Long userId, Long journeyId, String trainNo, int routeRevision,
                                    Integer rideLegIndex) {
        requireEnabled();
        Waiting waiting = waiting(userId, journeyId, rideLegIndex);
        if (waiting.session().getRouteRevision() != routeRevision) {
            record("stale");
            throw new StaleRouteRevisionException(waiting.session().getRouteRevision());
        }
        String lineId = waiting.waitLeg().getRouteId();
        String direction = SubwayDirectionNormalizer.fromWayCode(lineId, waiting.wayCode());
        LocalDate serviceDate = serviceDateResolver.resolve(clock.instant());
        boolean departed = false;
        // 후보 수가 아니라 지금 도착 목록 전체에서 찾는다 — 앞차가 막 들어와 순위가 밀린 열차를 거절하지 않게.
        // 탑승 중이면 승차역 도착 목록은 뒤차다 — 떠난 열차만 받는다.
        boolean riding = waiting.phase() == Phase.RIDING;
        List<String> cached = riding ? List.of() : trainNos(cachedArrivals(waiting));
        List<String> live = List.of();
        List<String> justDeparted = List.of();
        if (!riding && !cached.contains(trainNo)) {
            live = trainNos(liveArrivals(waiting));
        }
        if (!cached.contains(trainNo) && !live.contains(trainNo)) {
            justDeparted = justDeparted(waiting);
            if (!justDeparted.contains(trainNo)) {
                // 이 역을 이미 떠난 열차인지 본다(#299 의 departed 를 고른 경우). 그 열차의 운행일은 출발 사건의
                // 운행일이다 — 04:00 경계를 걸치면 지금 운행일과 다르다.
                Optional<LocalDate> departedOn = departedServiceDate(waiting, lineId, direction, trainNo);
                if (departedOn.isEmpty()) {
                    record("not-in-candidates");
                    // 요청 본문은 접근 로그에 안 남는다. 무엇을 골랐고 무엇이 보였는지 없으면 원인을 못 가른다.
                    log.warn("[SubwaySelection] 목록에 없는 열차 journeyId={} trainNo={} line={} station={} "
                                    + "direction={} cached={} live={} justDeparted={}",
                            journeyId, trainNo, lineId, waiting.waitLeg().getStartStopId(), direction,
                            cached, live, justDeparted);
                    throw new BusinessException(JourneyErrorCode.TRAIN_NOT_IN_CANDIDATES);
                }
                serviceDate = departedOn.get();
            }
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
        return departures(waiting, lineId, direction).stream()
                .filter(d -> trainNo.equals(d.trainNo()))
                .map(SubwayDepartureLookup.Departure::serviceDate)
                .findFirst();
    }

    /**
     * 이 역·방향을 창 안에 떠난 열차, 최근 순. 창은 보통 {@link #DEPARTED_WINDOW}, 탑승 중이면 탄 뒤 지난 시간까지 넓힌다 —
     * 20분째 탄 사람의 열차가 10분 창 밖으로 빠지면 "다른 열차예요"로 자기 열차를 못 고른다.
     */
    private List<SubwayDepartureLookup.Departure> departures(Waiting waiting, String lineId, String direction) {
        String stationId = waiting.waitLeg().getStartStopId();
        if (lineId == null || direction == null || stationId == null) {
            return List.of();
        }
        Instant now = clock.instant();
        try {
            return departureLookup.find(lineId, direction, stationId, now.minus(departedWindow(waiting, now)), now)
                    .stream()
                    .sorted(Comparator.comparing(SubwayDepartureLookup.Departure::departedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList();
        } catch (RuntimeException e) {
            log.warn("[SubwaySelection] 떠난 열차 조회 실패 line={} station={}: {}", lineId, stationId, e.getMessage());
            return List.of();
        }
    }

    private Duration departedWindow(Waiting waiting, Instant now) {
        Instant activatedAt = waiting.ride().getActivatedAt();
        if (waiting.phase() != Phase.RIDING || activatedAt == null) {
            return DEPARTED_WINDOW;
        }
        Duration ridden = Duration.between(activatedAt, now).plus(RIDING_WINDOW_SLACK);
        return ridden.compareTo(DEPARTED_WINDOW) > 0 ? ridden : DEPARTED_WINDOW;
    }

    /** 떠난 열차 후보 — 캐시의 막 떠난 열차(출발 기록 전 틈)를 앞에, 그다음 출발 기록. */
    private List<SubwayTrainCandidate> departedCandidates(Waiting waiting) {
        String lineId = waiting.waitLeg().getRouteId();
        String direction = SubwayDirectionNormalizer.fromWayCode(lineId, waiting.wayCode());
        List<SubwayTrainCandidate> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String trainNo : justDeparted(waiting)) {
            if (seen.add(trainNo)) {
                result.add(departedCandidate(trainNo, null));
            }
        }
        for (SubwayDepartureLookup.Departure departure : departures(waiting, lineId, direction)) {
            if (seen.add(departure.trainNo())) {
                result.add(departedCandidate(departure.trainNo(), departure.departedAt()));
            }
        }
        return result.stream().limit(DEPARTED_CANDIDATE_LIMIT).toList();
    }

    private static SubwayTrainCandidate departedCandidate(String trainNo, Instant departedAt) {
        return new SubwayTrainCandidate(trainNo, null, false, null, null, departedAt,
                SubwayTrainCandidate.Kind.DEPARTED);
    }

    /** 수집기 캐시 — 역 도착 노선도와 같은 원천이다. */
    private List<SubwayRealtimeArrivalReader.Arrival> cachedArrivals(Waiting waiting) {
        if (!hasStop(waiting)) {
            return List.of();
        }
        return arrivalReader.arrivals(waiting.waitLeg().getRouteId(), waiting.waitLeg().getStartStopId(),
                waiting.wayCode());
    }

    private List<SubwayRealtimeArrivalReader.Arrival> liveArrivals(Waiting waiting) {
        if (!hasStop(waiting)) {
            return List.of();
        }
        return arrivalReader.arrivalsByStation(waiting.waitLeg().getRouteId(), waiting.waitLeg().getStartStopId(),
                waiting.waitLeg().getStartName(), waiting.wayCode());
    }

    private List<String> justDeparted(Waiting waiting) {
        if (!hasStop(waiting)) {
            return List.of();
        }
        return arrivalReader.justDepartedTrainNos(waiting.waitLeg().getRouteId(), waiting.waitLeg().getStartStopId(),
                waiting.wayCode());
    }

    /** 방향을 모르면 어느 원천도 읽지 않는다 — 반대 방향 열차를 집는다. */
    private static boolean hasStop(Waiting waiting) {
        return waiting.wayCode() != null && waiting.waitLeg().getRouteId() != null
                && waiting.waitLeg().getStartStopId() != null;
    }

    private static List<String> trainNos(List<SubwayRealtimeArrivalReader.Arrival> arrivals) {
        return arrivals.stream().map(arrival -> arrival.train().getTrainNo()).toList();
    }

    /** 지하 환승에서 앞 탑승과 다음 대기 사이에 올 수 있는 도보 구간 수 상한. */
    static final int MAX_TRANSFER_WALK_LEGS = 1;

    private Waiting waiting(Long userId, Long journeyId, Integer rideLegIndex) {
        JourneySession session = activeSession(userId, journeyId);
        List<JourneyLeg> legs = legsOf(session);
        int current = session.getCurrentLegIndex();
        int rideIndex = rideLegIndex != null ? rideLegIndex : defaultRideIndex(legs, current);
        JourneyLeg ride = legAt(legs, rideIndex);
        JourneyLeg wait = legAt(legs, rideIndex - 1);
        Phase phase = isSubwayPair(wait, ride) ? phaseOf(legs, current, rideIndex) : null;
        if (phase == null) {
            record("not-waiting");
            throw new BusinessException(JourneyErrorCode.NOT_WAITING_FOR_SUBWAY);
        }
        return new Waiting(session, wait, ride, wayCodeOf(wait), phase);
    }

    private JourneySession activeSession(Long userId, Long journeyId) {
        JourneySession session = sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        if (session.getStatus() != JourneyStatus.ACTIVE) {
            throw new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE);
        }
        return session;
    }

    private List<JourneyLeg> legsOf(JourneySession session) {
        return legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                session.getId(), session.getRouteRevision());
    }

    /** 대상을 안 주면: 대기 중이면 다음 탑승, 탑승 중이면 그 탑승. 둘 다 아니면 다음 칸(검사에서 409 가 난다). */
    private static int defaultRideIndex(List<JourneyLeg> legs, int current) {
        JourneyLeg now = legAt(legs, current);
        if (now != null && now.getType() == JourneyLegType.RIDE_SUBWAY) {
            return current;
        }
        return current + 1;
    }

    private static boolean isSubwayPair(JourneyLeg wait, JourneyLeg ride) {
        return wait != null && wait.getType() == JourneyLegType.WAIT_SUBWAY
                && ride != null && ride.getType() == JourneyLegType.RIDE_SUBWAY;
    }

    /** 지금 단계에서 대상 탑승을 고를 수 있으면 그 경우, 아니면 null. */
    private static Phase phaseOf(List<JourneyLeg> legs, int current, int rideIndex) {
        if (current == rideIndex - 1) {
            return Phase.WAITING;
        }
        if (current == rideIndex) {
            return Phase.RIDING;
        }
        JourneyLeg now = legAt(legs, current);
        if (now == null || now.getType() != JourneyLegType.RIDE_SUBWAY || current >= rideIndex - 1) {
            return null;
        }
        int walks = 0;
        for (int i = current + 1; i < rideIndex - 1; i++) {
            JourneyLeg between = legAt(legs, i);
            if (between == null || (between.getType() != JourneyLegType.WALK
                    && between.getType() != JourneyLegType.TRANSFER_WALK)) {
                return null;
            }
            walks++;
        }
        return walks <= MAX_TRANSFER_WALK_LEGS ? Phase.TRANSFER : null;
    }

    private Integer wayCodeOf(JourneyLeg wait) {
        return Optional.ofNullable(wait.getSelectedRouteOptionId())
                .flatMap(routeOptionRepository::findById)
                .map(JourneyLegRouteOption::getWayCode)
                .orElse(null);
    }

    private static JourneyLeg legAt(List<JourneyLeg> legs, int index) {
        return legs.stream().filter(leg -> leg.getLegIndex() == index).findFirst().orElse(null);
    }

    private static SubwayTrainCandidate toCandidate(SubwayRealtimeArrivalReader.Arrival arrival) {
        String type = arrival.train().getTrainType();
        boolean express = type != null && type.contains("급행");
        return new SubwayTrainCandidate(arrival.train().getTrainNo(), arrival.train().getDestinationName(),
                express, type, arrival.arrivalSeconds(), observedAt(arrival.train().getReceivedAt()),
                SubwayTrainCandidate.Kind.UPCOMING);
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

    /** 어느 처지에서 고르는가 — 후보 원천이 갈린다(클래스 주석). */
    enum Phase { WAITING, RIDING, TRANSFER }

    private record Waiting(JourneySession session, JourneyLeg waitLeg, JourneyLeg ride, Integer wayCode,
                           Phase phase) {
    }
}
