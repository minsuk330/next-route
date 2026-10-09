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
        if (target != null && target.canonicalKey() != null) {
            SubwayRingObservation arrival = fromArrival(train, target, targetSequence);
            if (arrival != null) {
                return arrival;
            }
        }
        return fromPosition(train, chain);
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
        SubwayArrivalCode code = SubwayArrivalCode.from(row.getArrivalCode());
        if (code == SubwayArrivalCode.PREV_DEPARTED) {
            return measured(train, row, targetSequence, observedAt);
        }
        if (code == SubwayArrivalCode.PREV_ENTERING || code == SubwayArrivalCode.PREV_ARRIVED) {
            // 아직 전역에 서 있다. 남은 초는 출발 뒤 정해지는 정적 값이라 지금 시각에 더하면 틀린다 — 싣지 않는다.
            return SubwayRingObservation.arrival(code, targetSequence, null, observedAt);
        }
        return SubwayRingObservation.arrival(code, targetSequence,
                estimatedArrivalAt(code, row, observedAt), observedAt);
    }

    /**
     * 전역 출발(3) — 도착 예정 = 이 열차가 전역을 출발한 것으로 <b>처음</b> 보인 시각 + 이 구간을 최근 지난 열차들의 실측
     * 소요시간 중앙값(2026-10-07 결정).
     *
     * <p>{@code barvlDt} 는 쓰지 않는다. 전역 출발 뒤 실측보다 평균 54초 길고(전 노선 3,820구간), 같은 값이 반복되며
     * {@code recptnDt} 만 갱신돼 더하면 예정이 밀린다. 표본이 없으면 예정 없이 돌려준다 — 계산기가 멈춘다(STALE).
     */
    private SubwayRingObservation measured(TrainIdentity train, SubwayRealtimeTrain row, int targetSequence,
                                           Instant observedAt) {
        List<Long> recent = segmentTimePort.recentSeconds(row.getLineId(), row.getDirection(), row.getStationId());
        if (recent.isEmpty()) {
            return SubwayRingObservation.arrival(SubwayArrivalCode.PREV_DEPARTED, targetSequence, null, observedAt);
        }
        Instant start = segmentTimePort.departedAt(row.getLineId(), row.getDirection(), row.getStationId(),
                        train.trainNo())
                .filter(at -> !at.isAfter(observedAt))
                .orElse(observedAt);
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
    private SubwayRingObservation fromPosition(TrainIdentity train, SubwayRingChain chain) {
        Optional<SubwayTrainObservation> seen = trainLogPort.lastSeen(train);
        if (seen.isEmpty()) {
            return null;
        }
        SubwayTrainObservation observation = seen.get();
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
