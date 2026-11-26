package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.port.out.SubwayArrivalDepartureLogPort;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "이 역을 방금 떠난 열차"를 도착 API 수집 회차로 기록한다(#317-4, #645).
 *
 * <p>처음엔 위치 API 출발 사건(#299)이 없는 노선만 채웠다. #645 에서 전 노선으로 넓혔다 — 위치 출발 사건은 위치 API 가
 * 다음 역을 보여 줘야 생겨 약 1분 늦고, 그사이 도착 API 에서 행이 사라진 열차는 역 도착 응답 어디에도 없었다(노선 그림에서
 * 열차가 사라짐). 같은 열차가 두 원천에 있으면 {@link SubwayDepartureLookup} 이 위치 원천을 고른다 — 여기 기록은 빈틈만
 * 메운다. 도착 API 에서 다음 순간을 출발로 본다.
 * <ul>
 *   <li>직전 회차에 출발 전(그 밖의 코드)이던 그 역 행이 출발(2)이 됐다 → 그 행의 {@code recptnDt}</li>
 *   <li>직전 회차에 진입(0)·도착(1)·전역 출발(3)이던 그 역 행이 사라졌다 → 직전 관측과 이번 수집 사이 가운데.
 *       전역 출발(3)은 원천 갱신이 20~30초씩 건너뛰어 진입·도착·출발을 한 번에 지나치는 경우다(2026-11-26 실측, 2호선
 *       8461: 5 → 3 → 행 사라짐)</li>
 * </ul>
 *
 * <p><b>바뀌는 순간을 봤을 때만</b> 기록한다 — 재시작 첫 회차는 직전 상태가 없어 아무것도 안 남긴다
 * ({@link SubwaySegmentTimeRecorder} 배포 직후 함정과 같은 이유). 직전 상태는 메모리에 둔다 — 수집기는 worker 하나다.
 *
 * <p><b>부분 응답 가드</b>: 대상 노선 행 수가 직전 회차의 절반 미만이면 그 회차는 통째로 건너뛴다. 원천이 일부만 주면
 * 나머지 행이 전부 "사라짐"으로 읽혀 거짓 출발이 쏟아진다.
 *
 * <p>실패해도 수집을 막지 않는다 — 부르는 쪽이 감싼다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayArrivalDepartureRecorder {

    private final SubwayArrivalDepartureLogPort port;
    private final ServiceDateResolver serviceDateResolver;

    @Value("${realtime.subway.arrival-departure.enabled:true}")
    private boolean enabled;

    /** 전 노선 기록(#645). 끄면 {@link #lineIds} 만 — 위치 출발 사건이 없는 노선. */
    @Value("${realtime.subway.arrival-departure.all-lines:true}")
    private boolean allLines = true;

    /** 위치 출발 사건이 없는 노선(2026-10-07 실측). {@link #allLines} 를 끈 경우에만 본다. */
    @Value("${realtime.subway.arrival-departure.line-ids:1063,1067,1075,1081,1093,1094}")
    private Set<String> lineIds = Set.of();

    /** 이 코드였던 행이 사라지면 그 역을 떠난 것으로 본다. */
    private static final Set<String> VANISH_MEANS_DEPARTED = Set.of("0", "1", "3");

    private Map<String, Seen> previous = Map.of();

    public void record(List<SubwayRealtimeTrain> trains, Instant fetchedAt) {
        if (!enabled || trains == null) {
            return;
        }
        Map<String, Seen> current = new HashMap<>();
        for (SubwayRealtimeTrain train : trains) {
            if ((!allLines && !lineIds.contains(train.getLineId())) || train.getLineId() == null
                    || train.getTrainNo() == null || train.getStationId() == null
                    || train.getDirection() == null || train.getArrivalCode() == null) {
                continue;
            }
            Seen seen = new Seen(train.getLineId(), train.getDirection(), train.getStationId(), train.getTrainNo(),
                    train.getArrivalCode().trim(), SubwaySegmentTimeRecorder.receivedAt(train.getReceivedAt()),
                    train.getDestinationName(), train.getTrainType());
            current.put(seen.key(), seen);
        }
        if (!previous.isEmpty() && current.size() * 2 < previous.size()) {
            log.warn("[ArrivalDeparture] 부분 응답으로 보고 건너뜀 rows={} previous={}", current.size(), previous.size());
            return;
        }
        if (!previous.isEmpty()) {
            for (Seen now : current.values()) {
                Seen before = previous.get(now.key());
                if ("2".equals(now.code()) && before != null && !"2".equals(before.code())) {
                    append(now, now.receivedAt() != null ? now.receivedAt() : fetchedAt);
                }
            }
            for (Seen before : previous.values()) {
                if (!current.containsKey(before.key()) && VANISH_MEANS_DEPARTED.contains(before.code())) {
                    Instant last = before.receivedAt() != null && before.receivedAt().isBefore(fetchedAt)
                            ? before.receivedAt() : fetchedAt;
                    append(before, last.plus(Duration.between(last, fetchedAt).dividedBy(2)));
                }
            }
        }
        previous = current;
    }

    private void append(Seen seen, Instant departedAt) {
        port.append(serviceDateResolver.resolve(departedAt), seen.lineId(), seen.direction(), seen.stationId(),
                new SubwayArrivalDepartureLogPort.Row(seen.trainNo(), seen.destinationName(), seen.trainType(),
                        departedAt));
    }

    private record Seen(String lineId, String direction, String stationId, String trainNo, String code,
                        Instant receivedAt, String destinationName, String trainType) {

        String key() {
            return lineId + "|" + direction + "|" + stationId + "|" + trainNo;
        }
    }
}
