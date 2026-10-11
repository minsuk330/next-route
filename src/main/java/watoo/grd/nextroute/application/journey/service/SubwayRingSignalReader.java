package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.application.subway.port.out.SubwaySegmentTimePort;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 이번 tick 의 실시간 관측 한 건을 고른다. <b>두 원천을 역할로 나눈다.</b>
 *
 * <table>
 *   <tr><th>신호</th><th>주 원천</th><th>폴백</th></tr>
 *   <tr><td>남은 초</td><td>도착 {@code barvlDt}</td><td><b>없다</b> → freeze</td></tr>
 *   <tr><td>진입·도착</td><td>도착 {@code arvlCd 0/1}</td><td>위치 {@code trainSttus 0/1}</td></tr>
 *   <tr><td>구간 전환</td><td>도착 {@code arvlCd 1}</td><td>위치의 현재 역 변화</td></tr>
 * </table>
 *
 * <p>도착 행이 있어도 위치가 <b>진행이 더 앞선</b> 사건(전역 도착·출발 포함)을 말하면 위치를 쓴다(#371) — 같은 사건이
 * 도착 API 에는 15초쯤 늦게 보인다.
 *
 * <p>도착 API 는 역당 방향별 2대 정도만 준다. 실측으로 위치 열차의 3% 가 도착 목록에 아예 없고
 * 5% 는 먼 역에서만 잡혔다. 도착 하나에만 매달리면 그 열차를 탄 사람은 <b>여정 내내</b> 링이
 * 죽는다.
 *
 * <h2>조회는 공짜다</h2>
 *
 * <p>수집기가 전체 역을 한 번에 긁어 캐시에 넣어 두므로(실측 2907행/560역) 임의의 다음 역이
 * 이미 거기 있다. 역별 직접 호출은 <b>쓰지 않는다</b> — 호출량이 진행 중 여정 수에 비례해 늘고
 * 도착 API 는 일일 quota 를 공유한다.
 *
 * <p>전제는 전체 역 수집기({@code realtime.subway.enabled})가 켜져 있는 것이다. 꺼져 있으면
 * 캐시가 비어 언제나 위치 폴백이거나 freeze 다.
 *
 * <h2>{@code select()} 를 쓰지 않는다</h2>
 *
 * <p>{@code SubwayRealtimeArrivalReader.select()} 는 <b>{@code arvlCd=2}(조회역 출발) 열차를
 * 버린다</b>. 그건 "다음에 탈 열차 고르기"에는 맞지만 "내가 탄 열차 따라가기"에는 정반대다 —
 * 우리 열차는 그 역을 출발한 바로 그 열차다. 그걸 타면 링이 1.00 을 영영 못 찍는다.
 *
 * <p>거르는 근거가 초에서 {@code arvlCd} 로 바뀐 뒤에도 이 판단은 그대로다. 저쪽의 관심은
 * "아직 안 온 열차"이고 이쪽의 관심은 "지금 타고 있는 열차"라, 같은 목록에서 서로 반대편을 본다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayRingSignalReader {

    private static final DateTimeFormatter RECEIVED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SubwayRealtimeCachePort realtimeCachePort;
    private final SubwayTrainLogPort trainLogPort;
    private final SubwaySegmentTimePort segmentTimePort;

    /**
     * @param targetSequence 이번에 조회할 역의 체인 순번
     * @return 아무 원천도 우리 열차를 말해 주지 않으면 {@code null} — 부르는 쪽이 freeze 한다
     */
    public SubwayRingObservation read(TrainIdentity train, SubwayRingChain chain,
                                      int targetSequence) {
        SubwayRingChain.Station target = chain.at(targetSequence);
        SubwayRingObservation arrival = target != null && target.canonicalKey() != null
                ? fromArrival(train, target, targetSequence)
                : null;
        Optional<SubwayTrainObservation> seen = trainLogPort.lastSeen(train);
        if (arrival == null) {
            return seen.map(observation -> fromPosition(train, chain, observation)).orElse(null);
        }
        SubwayRingObservation ahead = seen
                .map(observation -> positionAhead(train, chain, targetSequence, arrival, observation))
                .orElse(null);
        return ahead != null ? ahead : arrival;
    }

    // ── 두 원천 중 앞선 쪽 (#371) ─────────────────────────────────────────────

    /** 위치가 도착 행보다 이만큼 넘게 묵었으면 앞서 보여도 쓰지 않는다 — 고른 순간 STALE 로 굳는다. */
    private static final java.time.Duration POSITION_MAX_BEHIND = java.time.Duration.ofSeconds(120);

    /**
     * 위치 API 가 도착 행보다 <b>진행이 앞선</b> 사건을 말하면 그것을 target 기준 관측으로 돌려준다. 아니면 null.
     *
     * <p>도착 API 는 같은 사건이 위치 API 보다 15초쯤 늦게 보인다(#371 실측). 그런데 둘을 {@code recptnDt} 로 견주면 안
     * 된다 — 도착 행은 내용이 그대로여도 수신 시각만 새로 찍힌다. 그래서 <b>진행 순위</b>(역 순번, 진입 &lt; 도착 &lt;
     * 출발)로 견주고, 위치가 엄격히 앞설 때만 위치를 쓴다. 같으면 도착(초를 가진 쪽)이다. 순위만 오르므로 두 원천이
     * tick 마다 엇갈려도 링이 뒤로 가지 않는다.
     *
     * <p>위치가 전역(target-1)의 사건이면 도착 코드 3/4/5 와 같은 관측(PREV_*)으로 바꿔 계산기의 전역 처리를 그대로
     * 탄다. 출처는 {@link SubwayRingObservation#fromPositionApi()} 로 따로 적는다.
     */
    private SubwayRingObservation positionAhead(TrainIdentity train, SubwayRingChain chain, int target,
                                                SubwayRingObservation arrival, SubwayTrainObservation position) {
        Instant observedAt = position.receivedAt();
        if (observedAt == null || observedAt.isBefore(arrival.observedAt().minus(POSITION_MAX_BEHIND))) {
            return null;
        }
        int sequence = chain.sequenceOfKey(position.stationId());
        if (sequence < 0) {
            // 구간 밖 — 도착 행이 우리 역에서 보이는 동안은 그쪽을 믿는다.
            return null;
        }
        int rank = rankOf(sequence, position.status());
        int arrivalRank = rankOf(target, arrival.code());
        if (rank < 0 || rank <= arrivalRank) {
            return null;
        }
        if (arrivalRank < 0 && !observedAt.isAfter(arrival.observedAt())) {
            // 99(남은 초)는 어디쯤인지 말하지 않는다 — 전역 출발 뒤 운행 중일 수도 있다. 그때는 순위로 못 견주니 더 새로운
            // 쪽만 믿는다. 안 그러면 묵은 "전역 도착"이 운행 중인 링을 정차로 되돌리고 예정을 지운다(SubwayRideScenarioIT).
            return null;
        }
        if (rank >= rank(target, ENTERING_STEP)) {
            return SubwayRingObservation.position(position.status(), sequence, observedAt);
        }
        int step = rank - rank(target - 1, 0);
        return switch (step) {
            case ENTERING_STEP -> SubwayRingObservation.arrival(SubwayArrivalCode.PREV_ENTERING, target, null,
                    observedAt).fromPositionApi();
            case ARRIVED_STEP -> SubwayRingObservation.arrival(SubwayArrivalCode.PREV_ARRIVED, target, null,
                    observedAt).fromPositionApi();
            case DEPARTED_STEP -> departedFromPosition(train, chain, target, observedAt).fromPositionApi();
            default -> null;
        };
    }

    private static final int ENTERING_STEP = 1;
    private static final int ARRIVED_STEP = 2;
    private static final int DEPARTED_STEP = 3;

    private static int rank(int sequence, int step) {
        return sequence * 4 + step;
    }

    /**
     * 위치 상태의 진행 순위. {@code trainSttus=3}(전역출발)은 그 역의 <b>바로 전역</b>을 출발했다는 뜻이다
     * ({@link SubwayTrainStatus#LEFT_PREVIOUS}, 사용자 확정 2026-10-09). 모르면 -1.
     */
    private static int rankOf(int sequence, SubwayTrainStatus status) {
        if (status == null) {
            return -1;
        }
        return switch (status) {
            case ENTERING -> rank(sequence, ENTERING_STEP);
            case ARRIVED -> rank(sequence, ARRIVED_STEP);
            case DEPARTED -> rank(sequence, DEPARTED_STEP);
            case LEFT_PREVIOUS -> sequence > 0 ? rank(sequence - 1, DEPARTED_STEP) : -1;
            case UNKNOWN -> -1;
        };
    }

    /** 도착 행(target 역 행) 코드의 진행 순위. 99·모름은 -1 — 어디쯤인지 말하지 않는다. */
    private static int rankOf(int target, SubwayArrivalCode code) {
        if (code == null) {
            return -1;
        }
        return switch (code) {
            case PREV_ENTERING -> rank(target - 1, ENTERING_STEP);
            case PREV_ARRIVED -> rank(target - 1, ARRIVED_STEP);
            case PREV_DEPARTED -> rank(target - 1, DEPARTED_STEP);
            case ENTERING -> rank(target, ENTERING_STEP);
            case ARRIVED -> rank(target, ARRIVED_STEP);
            case DEPARTED -> rank(target, DEPARTED_STEP);
            case ENROUTE, UNKNOWN -> -1;
        };
    }

    /**
     * 위치 API 로 본 전역 출발. 실측 표본은 도착 경로와 같은 키(<b>target 역</b>)로 읽는다. 출발 시각은 위치 로그의 전역
     * 출발 사건(처음 출발로 본 {@code recptnDt}) — {@code lastSeen} 의 수신 시각은 같은 상태여도 계속 갱신돼 사건 시각이
     * 아니다. 없으면 도착 경로의 출발 기록, 그것도 없으면 이 관측 시각.
     */
    private SubwayRingObservation departedFromPosition(TrainIdentity train, SubwayRingChain chain, int target,
                                                       Instant observedAt) {
        SubwayRingChain.Station to = chain.at(target);
        Instant departed = departureEvent(train, chain.at(target - 1), observedAt)
                .or(() -> segmentTimePort.departedAt(train.lineId(), train.direction(), to.canonicalKey(),
                        train.trainNo()))
                .filter(at -> !at.isAfter(observedAt))
                .orElse(observedAt);
        return measured(train.lineId(), train.direction(), to.canonicalKey(), target, departed, observedAt);
    }

    /** 출발 사건을 찾아볼 폭. 한 구간 운행보다 넉넉하고 같은 번호의 다음 운행(2호선 한 바퀴)보다 훨씬 짧다. */
    private static final java.time.Duration DEPARTURE_LOOKBACK = java.time.Duration.ofMinutes(10);

    private Optional<Instant> departureEvent(TrainIdentity train, SubwayRingChain.Station from, Instant observedAt) {
        if (from == null || from.canonicalKey() == null) {
            return Optional.empty();
        }
        try {
            return trainLogPort.findDepartures(train.serviceDate(), train.lineId(), train.direction(),
                            from.canonicalKey(), observedAt.minus(DEPARTURE_LOOKBACK), observedAt)
                    .stream()
                    .filter(departure -> train.trainNo().equals(departure.trainNo()))
                    .map(departure -> departure.event().to())
                    .filter(java.util.Objects::nonNull)
                    .max(Instant::compareTo);
        } catch (RuntimeException e) {
            log.debug("[SubwayRing] 전역 출발 사건 조회 실패 — {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ── 도착 API ────────────────────────────────────────────────────────────

    private SubwayRingObservation fromArrival(TrainIdentity train, SubwayRingChain.Station target,
                                              int targetSequence) {
        Optional<SubwayRealtimeSnapshot> snapshot = realtimeCachePort.readSnapshot();
        if (snapshot.isEmpty() || snapshot.get().getTrains() == null) {
            return null;
        }
        SubwayRealtimeTrain row = pick(snapshot.get().getTrains(), train, target.canonicalKey());
        if (row == null) {
            return null;
        }
        Instant observedAt = receivedAt(row);
        if (observedAt == null) {
            return null;
        }
        Instant presentAt = collectedAt(snapshot.get());
        SubwayArrivalCode code = SubwayArrivalCode.from(row.getArrivalCode());
        if (code == SubwayArrivalCode.PREV_DEPARTED) {
            Instant start = segmentTimePort.departedAt(row.getLineId(), row.getDirection(), row.getStationId(),
                            train.trainNo())
                    .filter(at -> !at.isAfter(observedAt))
                    .orElse(observedAt);
            return measured(row.getLineId(), row.getDirection(), row.getStationId(), targetSequence, start, observedAt)
                    .withPresentAt(presentAt);
        }
        if (code == SubwayArrivalCode.PREV_ENTERING || code == SubwayArrivalCode.PREV_ARRIVED) {
            // 아직 전역에 서 있다. 남은 초는 출발 뒤 정해지는 정적 값이라 지금 시각에 더하면 틀린다 — 싣지 않는다.
            return SubwayRingObservation.arrival(code, targetSequence, null, observedAt).withPresentAt(presentAt);
        }
        return SubwayRingObservation.arrival(code, targetSequence,
                estimatedArrivalAt(code, row, observedAt), observedAt).withPresentAt(presentAt);
    }

    /** 스냅샷 수집 시각(서버 시계). 이 행을 서버가 마지막으로 받아 본 때다. 못 읽으면 null — 계산기가 원천 시각 규칙으로 돌아간다. */
    private static Instant collectedAt(SubwayRealtimeSnapshot snapshot) {
        try {
            return snapshot.getCollectedAt() == null ? null
                    : java.time.OffsetDateTime.parse(snapshot.getCollectedAt()).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 전역 출발(3) — 도착 예정 = 이 열차가 전역을 출발한 것으로 <b>처음</b> 보인 시각 + 이 구간을 최근 지난 열차들의 실측
     * 소요시간 중앙값(2026-10-07 결정).
     *
     * <p>{@code barvlDt} 는 쓰지 않는다. 전역 출발 뒤 실측보다 평균 54초 길고(전 노선 3,820구간), 같은 값이 반복되며
     * {@code recptnDt} 만 갱신돼 더하면 예정이 밀린다. 표본이 없으면 예정 없이 돌려준다 — 계산기가 멈춘다(STALE).
     */
    private SubwayRingObservation measured(String lineId, String direction, String stationId, int targetSequence,
                                           Instant start, Instant observedAt) {
        List<Long> recent = segmentTimePort.recentSeconds(lineId, direction, stationId);
        if (recent.isEmpty()) {
            return SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, targetSequence, null, observedAt);
        }
        return SubwayRingObservation.measured(targetSequence, start, start.plusSeconds(median(recent)), observedAt);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    /**
     * 그 역 목록에서 <b>우리 열차</b>를 집는다. 남은 초로 거르지 않는다.
     *
     * <p>방향까지 보는 이유는 종착역·회차 구간에서 같은 번호가 반대 방향으로도 나오기 때문이다.
     * 방향 표기는 위치 수집기가 저장 시점에 도착 API 공간으로 맞춰 두었으므로 그대로 비교된다.
     */
    private SubwayRealtimeTrain pick(List<SubwayRealtimeTrain> trains, TrainIdentity train,
                                     String stationId) {
        for (SubwayRealtimeTrain row : trains) {
            if (stationId.equals(row.getStationId())
                    && train.lineId() != null && train.lineId().equals(row.getLineId())
                    && train.trainNo() != null && train.trainNo().equals(row.getTrainNo())
                    && (train.direction() == null || train.direction().equals(row.getDirection()))) {
                return row;
            }
        }
        return null;
    }

    /**
     * 남은 초를 <b>절대 시각</b>으로 바꾼다.
     *
     * <p>초를 그대로 들고 다니지 않는 이유는 응답의 {@code recptnDt} 가 행마다 다르고(실측
     * 210종) 이미 1~2분 뒤처져 있기 때문이다. "지금부터 3분"이 아니라 "그때부터 3분"이다.
     *
     * <p>{@code arvlCd} 가 사건(진입·도착)이면 초를 보지 않는다 — 그 자리의 0 은 "다 왔다"이지
     * 예정 시각이 아니다.
     */
    private Instant estimatedArrivalAt(SubwayArrivalCode code, SubwayRealtimeTrain row,
                                       Instant observedAt) {
        if (code != SubwayArrivalCode.ENROUTE && !code.isPreviousStation()) {
            return null;
        }
        Integer seconds = row.getArrivalSeconds();
        if (seconds == null || seconds <= 0) {
            // "[N]번째 전역" — 미산출이다. 만들어 내지 않는다.
            return null;
        }
        return observedAt.plusSeconds(seconds);
    }

    private Instant receivedAt(SubwayRealtimeTrain row) {
        if (row.getReceivedAt() == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(row.getReceivedAt(), RECEIVED_AT_FORMAT)
                    .atZone(SEOUL).toInstant();
        } catch (Exception e) {
            log.debug("[SubwayRing] recptnDt 파싱 실패 — {}", row.getReceivedAt());
            return null;
        }
    }

    // ── 위치 API 폴백 ───────────────────────────────────────────────────────

    /**
     * 도착 목록에 없을 때 <b>구간만은 잃지 않게</b> 한다.
     *
     * <p>남은 초는 못 얻으므로 링은 앵커에 멈추지만, 역이 바뀌면 다음 구간으로 정상 진행한다.
     */
    private SubwayRingObservation fromPosition(TrainIdentity train, SubwayRingChain chain,
                                               SubwayTrainObservation observation) {
        int sequence = chain.sequenceOfKey(observation.stationId());
        if (sequence < 0) {
            // 우리 구간 밖의 역이다. 구간을 이미 지나간 열차면 하차역 도착으로 마무리하고, 아니면(아직 승차역 전 등) 진행을 만들지 않는다.
            return passedChain(train, chain, observation) ? SubwayRingObservation.passed(chain.alightingSequence(),
                    observation.receivedAt()) : null;
        }
        return SubwayRingObservation.position(observation.status(), sequence,
                observation.receivedAt());
    }

    /** 구간 통과를 찾아볼 이력 폭. 방금 떠난 열차 선택 창(10분)보다 넉넉하고 2호선 한 바퀴(약 85분)보다 훨씬 짧다. */
    private static final java.time.Duration PASSED_LOOKBACK = java.time.Duration.ofMinutes(30);

    /**
     * 지금 구간 밖에 있는 열차가 구간을 <b>이미 지나갔는가</b>(#311).
     *
     * <p>"방금 떠난 열차"는 10분 창으로 고를 수 있어 짧은 구간에서는 하차역을 지난 열차가 고정된다(journey 166: 8484 가
     * 하차역 방배에 진입한 지 3분 뒤 선택). 그 열차는 구간 밖이라 지금까지는 링이 끝까지 STALE 이었다.
     *
     * <p>위치 로그의 역별 도착 이력에 구간 역 도착이 있고, 그 도착이 지금 관측(구간 밖)보다 이르면 구간을 지나 하차역 너머로
     * 간 것이다. 이력이 없으면 아직 승차역 전(상류)일 수 있으므로 판단하지 않는다.
     */
    private boolean passedChain(TrainIdentity train, SubwayRingChain chain, SubwayTrainObservation outside) {
        if (outside.receivedAt() == null) {
            return false;
        }
        try {
            return trainLogPort.findArrivals(train, outside.receivedAt().minus(PASSED_LOOKBACK), outside.receivedAt())
                    .stream()
                    .anyMatch(event -> chain.sequenceOfKey(event.stationId()) >= 0
                            && event.to() != null && !event.to().isAfter(outside.receivedAt()));
        } catch (RuntimeException e) {
            log.debug("[SubwayRing] 구간 통과 이력 조회 실패 — {}", e.getMessage());
            return false;
        }
    }
}
