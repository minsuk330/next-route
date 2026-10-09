package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyTrackProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartTrackCommand;
import watoo.grd.nextroute.application.route.dto.LaneGraphicResult;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPattern;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStopTime;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitTripRef;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.service.LegacyRouteResponseMapper;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 단독 추적 시작(#265). 경로 검색 없이 노선 패턴에서 구간을 오려 1구간 여정({@code kind=TRACK})을 연다.
 *
 * <pre>
 * 1. 패턴 결정(정류장 패턴 → 승차 다음 한 바퀴 안에 하차)    ← OTP, permit 하나
 * 2. 패턴 상세 + 오늘 trip 하나의 정차 시각 → RoutingLeg
 * 3. LegacyRouteResponseMapper.toSubPath → PathResult    ← 실시간 id·wayCode 보강을 검색과 같은 자리에서
 * 4. compile → enrich → startTx(kind=TRACK)              ← 일반 시작과 같다
 * </pre>
 *
 * <p>검색 이력은 남기지 않는다. 재탐색 보드 예약도 안 한다(PR-1 의 kind 가드).
 */
@Slf4j
@Service
public class TrackJourneyStartService {

    private static final ZoneId FEED_ZONE = ZoneId.of("Asia/Seoul");

    private final JourneyTrackProperties properties;
    private final TrackPatternSource source;
    private final LegacyRouteResponseMapper legacyMapper;
    private final JourneyCompiler compiler;
    private final JourneyTransitIdEnricher transitIdEnricher;
    private final JourneyStartTxService startTxService;
    private final JourneyStartConflictTranslator conflictTranslator;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public TrackJourneyStartService(JourneyTrackProperties properties, TrackPatternSource source,
                                    LegacyRouteResponseMapper legacyMapper, JourneyCompiler compiler,
                                    JourneyTransitIdEnricher transitIdEnricher,
                                    JourneyStartTxService startTxService,
                                    JourneyStartConflictTranslator conflictTranslator,
                                    ObjectMapper objectMapper, MeterRegistry meterRegistry, Clock clock) {
        this.properties = properties;
        this.source = source;
        this.legacyMapper = legacyMapper;
        this.compiler = compiler;
        this.transitIdEnricher = transitIdEnricher;
        this.startTxService = startTxService;
        this.conflictTranslator = conflictTranslator;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public JourneySnapshot start(Long userId, StartTrackCommand command) {
        if (!properties.isEnabled()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
        RoutingLeg leg;
        try {
            leg = source.withPermit(deadline -> buildLeg(command, deadline));
        } catch (BusinessException e) {
            record(null, e.getErrorCode().getCode());
            throw e;
        }

        PathResult path = toPath(legacyMapper.toSubPath(leg), leg);
        JourneyPlan plan = compiler.compile(path);
        if (plan.isEmpty()) {
            record(leg.mode(), "compile-failed");
            log.warn("[Track] 여정 쪼개기 실패 route={} board={} alight={}",
                    command.routeGtfsId(), command.boardStopId(), command.alightStopId());
            throw new BusinessException(JourneyErrorCode.JOURNEY_COMPILE_FAILED);
        }
        plan = transitIdEnricher.enrich(plan);

        JsonNode routeSnapshot = objectMapper.valueToTree(path);
        JsonNode destination = objectMapper.createObjectNode().put("name", path.info().lastEndStation());
        try {
            JourneySnapshot snapshot = startTxService.start(userId, plan, routeSnapshot, destination,
                    command.replace(), clock.instant(), JourneyKind.TRACK);
            record(leg.mode(), "started");
            return snapshot;
        } catch (DataIntegrityViolationException e) {
            record(leg.mode(), "conflict");
            throw conflictTranslator.translate(e, userId);
        } catch (RuntimeException e) {
            record(leg.mode(), "rejected");
            throw e;
        }
    }

    private RoutingLeg buildLeg(StartTrackCommand command, RoutingDeadline deadline) {
        // 노선 목록은 형제 정류장 노선까지 합친다 — 구버전 앱은 고른 정류장 id 로 다른 피드 노선을 시작한다.
        String boardStopId = source.boardingStop(command.routeGtfsId(), command.boardStopId(), deadline);
        List<TransitPattern> patterns = source.patternsAtStop(boardStopId, deadline);
        TrackPatternSelector.Selection selection = TrackPatternSelector.select(command.routeGtfsId(),
                        boardStopId, command.alightStopId(), command.patternCode(), patterns)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.TRACK_STOPS_INVALID));

        TransitPatternDetail detail = source.pattern(selection.pattern().code(), deadline)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE));
        if (detail.stops().size() != selection.pattern().stops().size()) {
            // 정류장 패턴(캐시)과 상세가 다른 그래프에서 왔다. 섞어 쓰면 위치가 어긋난다.
            throw new BusinessException(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE);
        }

        Trip trip = pickTrip(selection.pattern(), deadline)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.TRACK_SCHEDULE_UNAVAILABLE));
        List<TransitStopTime> times = source.port().tripStopTimes(trip.tripId(), trip.serviceDate(), deadline);
        if (times.size() == detail.stops().size()
                && (!times.get(selection.boardIndex()).pickup() || !times.get(selection.alightIndex()).dropoff())) {
            // 승차 전용 정류장에서 내리거나 하차 전용 정류장에서 타는 선택 — TAGO·시외버스에 흔하다.
            throw new BusinessException(JourneyErrorCode.TRACK_STOPS_INVALID);
        }
        try {
            return TrackLegFactory.build(detail, selection.boardIndex(), selection.alightIndex(),
                    times, trip.tripId(), trip.serviceDate(), source.graphVersion());
        } catch (TrackLegFactory.ScheduleUnavailableException e) {
            log.warn("[Track] 시간표 불일치 pattern={} trip={}: {}", detail.code(), trip.tripId(), e.getMessage());
            throw new BusinessException(JourneyErrorCode.TRACK_SCHEDULE_UNAVAILABLE);
        }
    }

    /**
     * 지금 시각에 가까운 trip 하나. 오늘 운행이 없으면 어제(자정 넘은 심야 운행), 그래도 없으면 실패다.
     *
     * <p>소요만 쓰므로 정확히 지금 차일 필요는 없다 — 첨두·비첨두 차이만 반영하면 된다. 그래서 첫 출발이 지금 이전인 것
     * 중 가장 늦은 것, 없으면 가장 이른 것.
     */
    private Optional<Trip> pickTrip(TransitPattern pattern, RoutingDeadline deadline) {
        ZonedDateTime now = clock.instant().atZone(FEED_ZONE);
        for (LocalDate date : List.of(now.toLocalDate(), now.toLocalDate().minusDays(1))) {
            List<TransitTripRef> trips = source.port().tripsForDate(pattern.code(), date, deadline);
            if (trips.isEmpty()) {
                continue;
            }
            long nowSec = Duration.between(date.atStartOfDay(FEED_ZONE), now).getSeconds();
            Optional<TransitTripRef> before = trips.stream()
                    .filter(t -> t.firstDepartureSec() != null && t.firstDepartureSec() <= nowSec)
                    .max(Comparator.comparingInt(TransitTripRef::firstDepartureSec));
            TransitTripRef chosen = before.orElseGet(() -> trips.stream()
                    .filter(t -> t.firstDepartureSec() != null)
                    .min(Comparator.comparingInt(TransitTripRef::firstDepartureSec))
                    .orElse(trips.get(0)));
            return Optional.of(new Trip(chosen.gtfsId(), date));
        }
        return Optional.empty();
    }

    private record Trip(String tripId, LocalDate serviceDate) {
    }

    /** 걷기 없는 1구간 경로. {@code info.lastEndStation} = 하차 이름(푸시 문구). */
    private static PathResult toPath(SubPathResult sub, RoutingLeg leg) {
        int pathType = leg.mode() == RoutingMode.BUS ? 2 : 1;
        PathInfo info = new PathInfo(sub.sectionTime(), LegacyRouteResponseMapper.PAYMENT_UNKNOWN, 0, 0,
                sub.startName(), sub.endName(), null);
        return new PathResult(pathType, info, List.of(sub), List.<LaneGraphicResult>of());
    }

    private void record(RoutingMode mode, String result) {
        meterRegistry.counter("track.start", "mode", mode == null ? "unknown" : mode.name(),
                "result", result).increment();
    }
}
