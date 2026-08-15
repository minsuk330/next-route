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
 * <p>{@code SubwayRealtimeArrivalReader.select()} 에는 {@code adjustedSeconds >= 0} 필터가 있어
 * <b>{@code barvlDt=0} 인 진입·도착 열차를 버린다</b>. 그걸 타면 링이 1.00 을 영영 못 찍는다.
 * 그 필터는 "다음에 탈 열차 고르기"에는 맞고 "내가 탄 열차 따라가기"에는 정반대다.
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
        return SubwayRingObservation.arrival(code, targetSequence,
                estimatedArrivalAt(code, row, observedAt), observedAt);
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
            // 우리 구간 밖의 역이다. 아무 진행도 만들지 않는다.
            return null;
        }
        return SubwayRingObservation.position(observation.status(), sequence,
                observation.receivedAt());
    }
}
