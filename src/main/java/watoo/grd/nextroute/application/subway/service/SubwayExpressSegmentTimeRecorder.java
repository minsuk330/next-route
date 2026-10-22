package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.application.subway.port.out.SubwayExpressSegmentTimePort;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 위치 API 관측에서 급행 구간 실측을 잰다(#497). 위치 추적기가 열차마다 직전·지금 관측을 넘긴다.
 *
 * <p>표본 = 정차역 도착(1)의 원천 시각 → 다음으로 도착(1)한 역의 원천 시각. 링 직선이 앞 역 도착에서 정차+운행으로 그려지고 하차
 * 예정이 구간 합이라, 양 끝을 같은 사건(도착)으로 맞춰야 구간을 더해도 치우치지 않는다 — 도착 → 진입으로 재면 구간마다 진입 →
 * 도착(20~40초)이 빠져 1948 재생에서 하차 예정이 75초 일렀다.
 * <ul>
 *   <li>1호선 코레일 구간은 출발(2)을 내지 않고, 급행은 지나치는 역에 행이 없다 — 도착 사이가 곧 정차역 사이다(2026-10-21 녹화, 1948
 *       안양 21:47:58 → 가산디지털단지 21:55:32 = 454초)</li>
 *   <li>9호선 급행은 지나치는 역에도 도착을 내 (지나치는 역, 정차역) 표본이 생긴다. 링은 체인의 이웃 정차역 쌍으로만 찾으므로 쓰이지
 *       않는다 — 9호선은 지나치는 역 관측으로 구간 안 진행을 이미 고친다</li>
 * </ul>
 * 역 순서는 보지 않는다 — 정차역을 하나 놓쳐 생긴 (A, C) 표본도 A→C 소요시간으로는 맞다. 종류는 열차 번호·{@code directAt} 로
 * 가른다(1948 은 구로부터 {@code directAt}=0 이지만 번호로 급행이다). 열차별 마지막 도착은 메모리에 둔다 — 추적기는 worker 하나다.
 */
@Component
@RequiredArgsConstructor
public class SubwayExpressSegmentTimeRecorder {

    /** 같은 역 재진입·원천 흔들림을 거른다. */
    static final long MIN_SECONDS = 30;
    /** 이보다 길면 운행 중단·결측이 섞였다(가장 긴 급행 구간 서울→금천구청도 15분 안팎). */
    static final long MAX_SECONDS = 25 * 60;
    /** 이만큼 쌓이면 {@link #MAX_SECONDS} 넘게 묵은 도착을 지운다. */
    private static final int PRUNE_AT = 2000;

    private final SubwayExpressSegmentTimePort port;
    private final SubwayTrainKindClassifier classifier;
    private final Map<String, Stop> lastArrivals = new ConcurrentHashMap<>();

    @Value("${realtime.subway.express-segment-time.enabled:true}")
    private boolean enabled = true;

    private record Stop(String stationId, Instant at) {
    }

    public void observe(TrainIdentity train, SubwayTrainObservation before, SubwayTrainObservation now) {
        if (!enabled || train == null || now == null || now.status() != SubwayTrainStatus.ARRIVED
                || now.stationId() == null || now.receivedAt() == null) {
            return;
        }
        if (before != null && before.isSameStation(now) && before.status() == SubwayTrainStatus.ARRIVED) {
            return; // 같은 도착이 수신 시각만 새로 찍혔다
        }
        if (classifier.classifyRaw(train.lineId(), train.trainNo(), null, now.directAtRaw()) != TrainKind.EXPRESS) {
            return;
        }
        prune(now.receivedAt());
        Stop previous = lastArrivals.put(train.asKeySuffix(), new Stop(now.stationId(), now.receivedAt()));
        if (previous == null || previous.stationId().equals(now.stationId())) {
            return;
        }
        long seconds = Duration.between(previous.at(), now.receivedAt()).toSeconds();
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            return;
        }
        port.record(train.lineId(), train.direction(), previous.stationId(), now.stationId(), seconds);
    }

    private void prune(Instant now) {
        if (lastArrivals.size() >= PRUNE_AT) {
            Instant floor = now.minusSeconds(MAX_SECONDS);
            lastArrivals.values().removeIf(stop -> stop.at().isBefore(floor));
        }
    }
}
