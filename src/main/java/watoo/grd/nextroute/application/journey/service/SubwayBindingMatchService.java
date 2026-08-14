package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneySubwayBindingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingCandidate;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingDecision;
import watoo.grd.nextroute.application.journey.observability.SubwayBindingMetrics;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.ServiceDateResolver;
import watoo.grd.nextroute.application.subway.service.SubwayDirectionNormalizer;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEvent;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEventType;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 판정 한 tick. <b>재료 모으기는 트랜잭션 밖, 쓰기는 짧은 트랜잭션 안</b>이다.
 *
 * <p>나눈 이유는 잠금 유지 구간을 짧게 두기 위해서다. Redis 왕복이 잠금 안에 들어가면 같은
 * 지하철 단계에 관측을 보내는 사용자가 그만큼 막힌다. 지금 대기가 짧은 이유는 쓰기 트랜잭션에
 * DB 왕복밖에 없기 때문이지 잠금이 싸서가 아니다.
 *
 * <h2>못 고르는 것이 기본이다</h2>
 *
 * <p>원천은 1~2분 뒤처져 있어 사용자가 탄 직후에는 그 열차의 출발이 아직 관측되지 않았을 수
 * 있다. 그래서 한 번 보고 끝내지 않고 tick 마다 다시 시도한다. 매 tick 아무 일도 없는 것이
 * 정상 동작이고, 그때는 아무 행도 쓰지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayBindingMatchService {

    /** 후보·근거를 되찾을 때 거슬러 읽는 감사행 수. 한 단계의 관측·판단이 이 안에 들어온다. */
    private static final int AUDIT_SCAN_LIMIT = 100;

    private final SubwayLegBindingRepository bindingRepository;
    private final SubwayBindingEventRepository eventRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final JourneySubwayStationChainReader stationChainReader;
    private final SubwayTrainLogPort trainLogPort;
    private final ServiceDateResolver serviceDateResolver;
    private final SubwayTrainMatcher matcher;
    private final SubwayBindingMatchTxService txService;
    private final SubwayBindingMetrics metrics;
    private final JourneySubwayBindingProperties properties;
    private final Clock clock;

    /**
     * 한 tick.
     *
     * <p>한 건의 실패는 그 건만 격리한다 — 나머지는 계속 처리하고, 실패한 것은 다음 tick 에
     * 다시 잡힌다(행이 그대로 남아 있다).
     *
     * @return 훑은 건수
     */
    public int runOnce() {
        List<SubwayLegBinding> batch = bindingRepository.findMatchable(properties.getBatchSize());
        metrics.recordScanned(batch.size());
        for (SubwayLegBinding binding : batch) {
            try {
                processOne(binding);
            } catch (Exception e) {
                metrics.recordFailure("transient");
                log.warn("[SubwayBinding] 판정 실패 — journeyLegId={}: {}",
                        binding.getJourneyLegId(), e.getMessage(), e);
            }
        }
        return batch.size();
    }

    /**
     * 이동 시작 근거 없이 오래 남은 {@code PENDING} 을 접는다.
     *
     * <p>{@code findMatchable} 이 일부러 안 보는 행들이라 여기서 접지 않으면 영원히 남는다.
     * 스캔에 남겨 두면 가장 오래된 그 행들이 매 tick 배치를 통째로 차지해 뒤 행이 굶는다.
     *
     * <p>문장 하나가 곧 잠금이라 따로 행을 잡지 않는다. 다만 {@code @Modifying} 질의라
     * 트랜잭션 안에서만 돌 수 있다.
     *
     * @return 접은 행 수
     */
    @Transactional
    public int sweepStale() {
        LocalDateTime now = LocalDateTime.now(clock);
        return bindingRepository.abandonStaleWithoutMovement(
                now, now.minus(properties.getMatchWindow()));
    }

    // ── 한 건 ──────────────────────────────────────────────────────────────

    private void processOne(SubwayLegBinding binding) {
        JourneyLeg ride = legRepository.findById(binding.getJourneyLegId()).orElse(null);
        if (ride == null) {
            return;
        }
        Recovered recovered = recover(binding);
        SubwayBindingDecision decision = matcher.match(buildInput(binding, ride, recovered));
        metrics.recordDecision(decision);
        if (!decision.hasWrite()) {
            log.debug("[SubwayBinding] 고정 없음 — journeyLegId={} reason={}",
                    binding.getJourneyLegId(), decision.reason());
            return;
        }
        metrics.recordApplied(txService.apply(binding, decision));
    }

    private SubwayTrainMatcher.MatchInput buildInput(SubwayLegBinding binding, JourneyLeg ride,
                                                     Recovered recovered) {
        String lineId = ride.getRouteId();
        String direction = SubwayDirectionNormalizer.fromWayCode(lineId, wayCodeOf(ride));
        String boardStationId = ride.getStartStopId();
        Instant now = Instant.now(clock);

        boolean confirming = binding.getStatus() == SubwayBindingStatus.PROVISIONAL;
        List<SubwayBindingCandidate> departures = confirming
                ? List.of()
                : findDepartures(recovered.movementStartedAt(), lineId, direction, boardStationId);
        Map<TrainIdentity, List<SubwayTrainEvent>> arrivals = confirming
                ? findArrivals(recovered)
                : Map.of();

        return new SubwayTrainMatcher.MatchInput(
                binding.getStatus(), recovered.movementStartedAt(), departures,
                recovered.rejected(), lineId, direction, boardStationId, now,
                recovered.clockSuspect(),
                recovered.episodeFrom(), recovered.episodeTo(), currentTrainOf(binding),
                plannedNextStation(binding, ride, recovered),
                recovered.candidates(), arrivals, recovered.provisionalDecidedAt());
    }

    /**
     * 방향의 원천은 짝 노선 후보의 {@code wayCode} 다.
     *
     * <p>고정은 타는 단계가 소유하지만 노선 후보는 <b>기다리는 단계</b>가 소유한다. 타는 단계가
     * 같은 후보를 가리키도록 저장되지만, 그렇지 않은 경로를 대비해 앞 단계까지 본다.
     */
    private Integer wayCodeOf(JourneyLeg ride) {
        Optional<JourneyLegRouteOption> own = Optional.ofNullable(ride.getSelectedRouteOptionId())
                .flatMap(routeOptionRepository::findById);
        if (own.isPresent()) {
            return own.get().getWayCode();
        }
        return legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        ride.getJourneyId(), ride.getRouteRevision()).stream()
                .filter(leg -> leg.getLegIndex() == ride.getLegIndex() - 1)
                .map(JourneyLeg::getSelectedRouteOptionId)
                .filter(Objects::nonNull)
                .findFirst()
                .flatMap(routeOptionRepository::findById)
                .map(JourneyLegRouteOption::getWayCode)
                .orElse(null);
    }

    private TrainIdentity currentTrainOf(SubwayLegBinding binding) {
        if (binding.getTrainNo() == null) {
            return null;
        }
        return new TrainIdentity(binding.getTrainServiceDate(), binding.getTrainLineId(),
                binding.getTrainDirection(), binding.getTrainNo());
    }

    private String plannedNextStation(SubwayLegBinding binding, JourneyLeg ride,
                                      Recovered recovered) {
        if (recovered.episodeSequence() == null) {
            return null;
        }
        List<String> chain = stationChainReader.stationNames(
                binding.getJourneyId(), binding.getRouteRevision(), ride.getLegIndex());
        return stationChainReader.stationAfterBoarding(chain, recovered.episodeSequence());
    }

    // ── 열차 로그 조회 ──────────────────────────────────────────────────────

    /**
     * 탑승역을 그 시간대에 출발한 열차들.
     *
     * <p>조회창을 {@code indexHalfWidthGuard} 만큼 넓게 긁고 코드에서 다시 거른다. 색인 점수가
     * 사건 구간의 <b>중간점</b>이라, 창을 그대로 쓰면 실제로는 겹치는데 중간점이 창 밖이라 후보에
     * 오르지도 못한다.
     *
     * <p>영업일 경계는 자정이 아니라 <b>04:00</b> 이다. 구간이 그 선을 걸치면 두 날짜를 모두
     * 조회해 합친다 — 자정으로 착각하면 심야에 후보가 통째로 빈다.
     */
    private List<SubwayBindingCandidate> findDepartures(Instant movementStartedAt, String lineId,
                                                        String direction, String stationId) {
        if (movementStartedAt == null || lineId == null || direction == null || stationId == null) {
            return List.of();
        }
        Duration lookback = properties.getDepartureLookback();
        Instant windowFrom = movementStartedAt.minus(lookback);
        Instant windowTo = movementStartedAt.plus(lookback);
        Duration guard = properties.getIndexHalfWidthGuard();

        List<SubwayBindingCandidate> found = new ArrayList<>();
        for (LocalDate serviceDate : serviceDatesOf(windowFrom, windowTo)) {
            for (SubwayTrainLogPort.TrainDeparture row : trainLogPort.findDepartures(
                    serviceDate, lineId, direction, stationId,
                    windowFrom.minus(guard), windowTo.plus(guard))) {
                if (overlaps(row.event(), windowFrom, windowTo)) {
                    found.add(SubwayBindingCandidate.of(
                            serviceDate, lineId, direction, row.trainNo(), row.event()));
                }
            }
        }
        return found;
    }

    /** 후보별 도착 로그. 비교역 필터는 판정기가 이름으로 한다 — 여기서는 시간창만 좁힌다. */
    private Map<TrainIdentity, List<SubwayTrainEvent>> findArrivals(Recovered recovered) {
        if (recovered.episodeFrom() == null || recovered.episodeTo() == null
                || recovered.candidates().isEmpty()) {
            return Map.of();
        }
        Duration lookaround = properties.getArrivalLookaround();
        Duration guard = properties.getIndexHalfWidthGuard();
        Instant windowFrom = recovered.episodeFrom().minus(lookaround);
        Instant windowTo = recovered.episodeTo().plus(lookaround);

        Map<TrainIdentity, List<SubwayTrainEvent>> byTrain = new HashMap<>();
        for (SubwayBindingCandidate candidate : recovered.candidates()) {
            TrainIdentity identity = candidate.identity();
            if (byTrain.containsKey(identity)) {
                continue;
            }
            List<SubwayTrainEvent> events = trainLogPort
                    .findArrivals(identity, windowFrom.minus(guard), windowTo.plus(guard)).stream()
                    .filter(event -> overlaps(event, windowFrom, windowTo))
                    .toList();
            byTrain.put(identity, events);
        }
        return byTrain;
    }

    /** 04:00 을 걸치면 두 날짜다. 같으면 한 번만 조회한다. */
    private Set<LocalDate> serviceDatesOf(Instant from, Instant to) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        dates.add(serviceDateResolver.resolve(from));
        dates.add(serviceDateResolver.resolve(to));
        return dates;
    }

    private boolean overlaps(SubwayTrainEvent event, Instant from, Instant to) {
        return !event.from().isAfter(to) && !from.isAfter(event.to());
    }

    // ── 감사행에서 근거 되찾기 ──────────────────────────────────────────────

    /**
     * 관측·판단 원본에서 판정 재료를 되찾는다.
     *
     * <p>칼럼이 아니라 감사행에서 읽는 이유는 판정에 쓸 값이 아직 굳지 않았기 때문이다. 관문을
     * 한 번 조정할 때마다 필요한 필드가 바뀌는데 그때마다 마이그레이션을 만들 수는 없다.
     *
     * <p><b>세대와 순번을 맞춘 행만 읽는다.</b> 사용자 reset 이 있었다면 그 이전 관측은 거부된
     * 근거이고, 순번이 다르면 이미 지난 관측이다. 맞추지 않으면 방금 거부한 열차를 그대로 다시 고른다.
     */
    private Recovered recover(SubwayLegBinding binding) {
        List<SubwayBindingEvent> rows = eventRepository.findByJourneyLegIdOrderByIdDesc(
                binding.getJourneyLegId(), Limit.of(AUDIT_SCAN_LIMIT));

        SubwayBindingEvent movement = latest(rows, SubwayBindingEventType.MOVEMENT_START,
                binding.getResetGeneration(), binding.getLastMovementSeq());
        SubwayBindingEvent episode = latest(rows, SubwayBindingEventType.STATION_STOP_EPISODE,
                binding.getResetGeneration(), binding.getLastEpisodeSeq());
        SubwayBindingEvent provisional = rows.stream()
                .filter(row -> row.getEventType() == SubwayBindingEventType.PROVISIONAL)
                .filter(row -> row.getResetGeneration() == binding.getResetGeneration())
                .filter(row -> Objects.equals(row.getBindingVersion(), binding.getVersion()))
                .findFirst()
                .orElse(null);

        Instant stationaryAt = instantOf(episode, "stationaryAt");
        Instant resumedAt = instantOf(episode, "resumedAt");
        return new Recovered(
                instantOf(movement, "movementStartedAt"),
                clockSuspect(movement) || clockSuspect(episode),
                stationaryAt,
                resumedAt == null ? stationaryAt : resumedAt,
                episode == null ? null : episode.getEvidenceSequence(),
                rejectedTrains(rows),
                candidatesOf(provisional),
                provisional == null ? null : provisional.getReceivedAt());
    }

    private SubwayBindingEvent latest(List<SubwayBindingEvent> rows, SubwayBindingEventType type,
                                      int resetGeneration, Integer sequence) {
        if (sequence == null) {
            return null;
        }
        return rows.stream()
                .filter(row -> row.getEventType() == type)
                .filter(row -> row.getResetGeneration() == resetGeneration)
                .filter(row -> Objects.equals(row.getEvidenceSequence(), sequence))
                .findFirst()
                .orElse(null);
    }

    /**
     * 사용자가 아니라고 한 열차들.
     *
     * <p>세대를 가리지 않고 전부 모은다. 거부는 누적되는 사실이라 세대가 올랐다고 되살아나면
     * 안 된다 — 그러면 판정기가 같은 관측을 다시 읽어 방금 거부한 열차를 그대로 다시 고른다.
     */
    private Set<TrainIdentity> rejectedTrains(List<SubwayBindingEvent> rows) {
        Set<TrainIdentity> rejected = new HashSet<>();
        for (SubwayBindingEvent row : rows) {
            if (row.getEventType() != SubwayBindingEventType.USER_RESET
                    || row.getChosenTrainNo() == null) {
                continue;
            }
            rejected.add(new TrainIdentity(row.getChosenServiceDate(), row.getChosenLineId(),
                    row.getChosenDirection(), row.getChosenTrainNo()));
        }
        return rejected;
    }

    /** 잠정 판정 때 함께 남긴 후보. 캐시가 날아가도 여기서 되찾아 교정할 수 있다. */
    private List<SubwayBindingCandidate> candidatesOf(SubwayBindingEvent provisional) {
        JsonNode detail = provisional == null ? null : provisional.getDetail();
        if (detail == null || !detail.path("candidates").isArray()) {
            return List.of();
        }
        List<SubwayBindingCandidate> candidates = new ArrayList<>();
        for (JsonNode row : detail.get("candidates")) {
            SubwayBindingCandidate candidate = candidateOf(row);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return candidates;
    }

    private SubwayBindingCandidate candidateOf(JsonNode row) {
        String trainNo = text(row, "trainNo");
        String serviceDate = text(row, "serviceDate");
        String lineId = text(row, "lineId");
        String direction = text(row, "direction");
        String from = text(row, "departureFrom");
        String to = text(row, "departureTo");
        if (trainNo == null || serviceDate == null || lineId == null || direction == null
                || from == null || to == null) {
            // 모양이 깨진 행은 조용히 버린다. 반쪽 identity 는 어느 열차도 가리키지 못한다.
            return null;
        }
        return new SubwayBindingCandidate(trainNo, LocalDate.parse(serviceDate), lineId, direction,
                Instant.parse(from), Instant.parse(to),
                enumOf(SubwayTrainEvent.Quality.class, text(row, "quality")),
                text(row, "terminalId"),
                enumOf(SubwayExpressType.class, text(row, "express")),
                row.path("gapSeconds").asLong(0L));
    }

    private <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText();
    }

    private Instant instantOf(SubwayBindingEvent event, String field) {
        String raw = event == null || event.getDetail() == null
                ? null
                : text(event.getDetail(), field);
        try {
            return raw == null ? null : Instant.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean clockSuspect(SubwayBindingEvent event) {
        return event != null && event.getDetail() != null
                && event.getDetail().path("clockSuspect").asBoolean(false);
    }

    /** 감사행에서 되찾은 판정 재료 한 묶음. */
    private record Recovered(
            Instant movementStartedAt,
            boolean clockSuspect,
            Instant episodeFrom,
            Instant episodeTo,
            Integer episodeSequence,
            Set<TrainIdentity> rejected,
            List<SubwayBindingCandidate> candidates,
            Instant provisionalDecidedAt) {
    }
}
