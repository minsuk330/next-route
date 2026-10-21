package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.application.subway.port.out.SubwaySegmentTimePort;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 도착 API 수집 한 회차에서 구간 실측 표본을 쌓는다(진행 링의 도착 예정 원천).
 *
 * <p>그 역 행이 {@code arvlCd=3}(전역 출발)이면 출발, {@code 1}(도착)·{@code 0}(진입)이면 도착이다. 시각은 그 행의
 * {@code recptnDt}. 진입을 도착으로 함께 받는 이유는 도착(1) 행이 안 잡히고 바로 출발(2)로 넘어가는 회차가 있어서다 —
 * 표본 기준도 같다(2026-10-07 실측의 "실제 도착" = 1, 없으면 0).
 *
 * <p><b>출발은 바뀌는 순간을 봤을 때만 기록한다</b> — 직전 회차에 같은 열차·역이 출발 전 상태(5·4·99)였을 때. 재시작·
 * 첫차 시간(수집기 05:30 시작)의 첫 회차에는 이미 출발해 있던 열차가 "방금 출발"로 잡혀 소요시간이 수집 주기(15초)로
 * 짧게 남았다(2026-10-08 배포 직후 실측). 직전 상태는 메모리에 둔다 — 수집기는 worker 하나다.
 *
 * <p>실패해도 수집을 막지 않는다 — 표본이 없으면 링이 기존대로 멈출 뿐이다.
 *
 * <p><b>일반 열차만 표본으로 쓴다</b>(subway-express PR-S3). 급행은 역을 건너뛰어 같은 역 쌍이라도 시간이 다르고(9호선 급행
 * 가양→염창 210초 vs 완행 합 310초), ITX·직통도 그렇다. 종류는 행이 아니라 열차 단위다 — 같은 급행 열차의 어느 역 행은
 * '일반'으로 온다(운영 스냅숏 13/606편). 그래서 이 회차의 그 열차 모든 행으로 {@link SubwayTrainKindClassifier#classifyTrain}
 * 한 결과가 일반일 때만 남긴다({@link SubwayTrainKindClassifier#actsAsNormal} — 근거 없는 UNKNOWN 은 일반 열차만 다니는
 * 노선에서만 남는다). 1호선 구로 급행(14xx)은 모든 역에 서는 일반이라 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwaySegmentTimeRecorder {

    private static final DateTimeFormatter RECEIVED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final SubwaySegmentTimePort port;
    private final SubwayTrainKindClassifier classifier;

    /** 직전 회차의 (노선|방향|역|열차) → 도착 코드. 회차마다 통째로 갈아 끼운다. */
    private Map<String, String> previousCodes = Map.of();

    @Value("${realtime.subway.segment-time.enabled:true}")
    private boolean enabled;

    public void record(List<SubwayRealtimeTrain> trains) {
        if (!enabled || trains == null) {
            return;
        }
        Map<String, String> current = new HashMap<>(trains.size() * 2);
        Map<String, Boolean> normal = normalTrains(trains);
        for (SubwayRealtimeTrain train : trains) {
            String code = train.getArrivalCode();
            if (code == null || train.getTrainNo() == null || train.getStationId() == null
                    || train.getLineId() == null || train.getDirection() == null) {
                continue;
            }
            if (!normal.getOrDefault(train.getLineId() + "|" + train.getTrainNo(), false)) {
                continue;
            }
            String key = train.getLineId() + "|" + train.getDirection() + "|" + train.getStationId() + "|"
                    + train.getTrainNo();
            current.put(key, code);
            boolean departed = "3".equals(code) && isBeforeDeparture(previousCodes.get(key));
            boolean arrived = "1".equals(code) || "0".equals(code);
            if (!departed && !arrived) {
                continue;
            }
            Instant at = receivedAt(train.getReceivedAt());
            if (at == null) {
                continue;
            }
            if (departed) {
                port.recordDeparted(train.getLineId(), train.getDirection(), train.getStationId(), train.getTrainNo(), at);
            } else {
                port.recordArrived(train.getLineId(), train.getDirection(), train.getStationId(), train.getTrainNo(), at);
            }
        }
        previousCodes = current;
    }

    /** (노선|원문 번호) → 일반 열차인가. 이 회차의 그 열차 모든 행이 근거다. */
    private Map<String, Boolean> normalTrains(List<SubwayRealtimeTrain> trains) {
        Map<String, List<SubwayTrainKindClassifier.Sighting>> byTrain = new HashMap<>();
        for (SubwayRealtimeTrain train : trains) {
            if (train.getLineId() == null || train.getTrainNo() == null) {
                continue;
            }
            byTrain.computeIfAbsent(train.getLineId() + "|" + train.getTrainNo(), key -> new java.util.ArrayList<>())
                    .add(SubwayTrainKindClassifier.Sighting.ofTrainType(train.getTrainType()));
        }
        Map<String, Boolean> normal = new HashMap<>(byTrain.size() * 2);
        byTrain.forEach((key, sightings) -> {
            int bar = key.indexOf('|');
            String lineId = key.substring(0, bar);
            TrainKind kind = classifier.classifyTrain(lineId, key.substring(bar + 1), sightings);
            normal.put(key, classifier.actsAsNormal(lineId, kind));
        });
        return normal;
    }

    /** 전역 진입·도착(4·5) 또는 2역 이상 전(99) — 아직 이 구간을 출발하지 않은 상태. */
    private static boolean isBeforeDeparture(String previousCode) {
        return "4".equals(previousCode) || "5".equals(previousCode) || "99".equals(previousCode);
    }

    static Instant receivedAt(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, RECEIVED_AT).atZone(SEOUL).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
