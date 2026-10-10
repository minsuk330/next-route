package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.PositionSource;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Segment;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.SegmentSource;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.TrainStatus;
import watoo.grd.nextroute.application.arrival.service.SubwayTrainPositionResolver.Position;
import watoo.grd.nextroute.application.subway.port.out.SubwaySegmentTimePort;
import watoo.grd.nextroute.application.subway.service.SubwayLineOrder;
import watoo.grd.nextroute.application.subway.service.SubwayLineStationIndex;
import watoo.grd.nextroute.application.subway.service.SubwayPatternStationMatcher;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 역 도착 응답 열차마다 "지금 달리는 구간"과 그 출발·도착 예정(#317-2·#318-3), 그리고 이 역을 이미 지난 열차 판정.
 *
 * <p><b>도착 예정은 GTFS 계획 초다</b>(2026-10-10 사용자 결정). #316 구간 실측은 "전역 출발 → 진입(0)"을 재서 진입 신호가
 * 역에 닿기 한참 전에 오는 구간에서 16~25초로 짧게 나온다(계획 90초). 링 ETA 에는 그 오차가 작지만, 앱이 이 값으로 열차를
 * 움직이면 금방 구간 90% 에 닿아 멈춰 기다린다. 그래서 여기서는 실측을 쓰지 않는다 — 링 쪽 실측은 그대로다.
 *
 * <p>구간은 출발한 열차에만 있다. 정차·진입 중이면 앱은 역에 둔다.
 *
 * <p>값을 못 읽으면(Redis 실패 등) 구간을 비울 뿐 목록은 그대로다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayTrainSegmentResolver {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 지난 열차 판정에 보는 하류 역 수. 위치 원천 지연(최대 150초)에 열차가 갈 수 있는 거리보다 넉넉하게. */
    static final int PASSED_LOOKAHEAD = 3;
    /** 상류로 보는 역 수(노선도 오는 쪽 역과 같은 수). 순환선에서 상·하류가 겹치면 지나지 않은 것으로 본다. */
    static final int UPSTREAM_LOOKBACK = 4;

    private final SubwayLineOrder lineOrder;
    private final SubwaySegmentTimePort segmentTimePort;
    private final SubwayLineStationIndex stationIndex;

    /** @return 출발한 열차가 아니거나 다음 역·출발 시각을 모르면 null */
    public Segment segment(String lineId, String direction, String trainNo, Position position) {
        if (position == null || position.status() != TrainStatus.DEPARTED || position.stationId() == null
                || trainNo == null) {
            return null;
        }
        String from = position.stationId();
        Optional<String> to = lineOrder.nextOf(lineId, direction, from);
        if (to.isEmpty()) {
            return null;
        }
        Instant departedAt = measuredDeparture(lineId, direction, to.get(), trainNo)
                .orElse(position.source() == PositionSource.POSITION ? position.observedAt() : null);
        if (departedAt == null) {
            return null; // 도착 API 확인 시각은 상태가 그대로여도 갱신된다 — 출발 시각으로 쓰지 않는다
        }
        Integer seconds = lineOrder.runSeconds(lineId, direction, from, to.get()).orElse(null);
        SegmentSource source = seconds == null ? null : SegmentSource.SCHEDULED;
        return new Segment(from, position.stationName() != null ? position.stationName() : nameOf(lineId, from),
                to.get(), nameOf(lineId, to.get()),
                OffsetDateTime.ofInstant(departedAt, SEOUL),
                seconds == null ? null : OffsetDateTime.ofInstant(departedAt.plusSeconds(seconds), SEOUL),
                source);
    }

    /**
     * 이 역을 이미 지난 열차인지(결정: PR-1 은 반대 방향만, 지난 열차는 역 순서가 생긴 뒤 여기서).
     * <ul>
     *   <li>위치 API 가 "조회역 출발"이라 했고 그 관측이 도착 행보다 최신 — reader 는 도착 행 출발(2)만 빼서, 도착 행이
     *       아직 진입·도착(0·1)인 동안 남는다</li>
     *   <li>현재 역이 조회역 하류 {@value #PASSED_LOOKAHEAD} 역 안이고 상류 {@value #UPSTREAM_LOOKBACK} 역·조회역에 없다</li>
     * </ul>
     */
    public boolean hasPassed(String lineId, String direction, String boardStationId, Position position,
                             Instant arrivalRowReceivedAt) {
        if (position == null || position.stationId() == null || boardStationId == null) {
            return false;
        }
        if (position.source() == PositionSource.POSITION && position.status() == TrainStatus.DEPARTED
                && boardStationId.equals(position.stationId()) && position.observedAt() != null
                && (arrivalRowReceivedAt == null || position.observedAt().isAfter(arrivalRowReceivedAt))) {
            return true;
        }
        Set<String> downstream = new HashSet<>(chain(lineId, direction, boardStationId, PASSED_LOOKAHEAD, true));
        if (!downstream.contains(position.stationId())) {
            return false;
        }
        Set<String> upstream = new HashSet<>(chain(lineId, direction, boardStationId, UPSTREAM_LOOKBACK, false));
        upstream.add(boardStationId);
        return !upstream.contains(position.stationId());
    }

    private List<String> chain(String lineId, String direction, String start, int count, boolean forward) {
        List<String> out = new ArrayList<>(count);
        String current = start;
        for (int i = 0; i < count; i++) {
            Optional<String> next = forward ? lineOrder.nextOf(lineId, direction, current)
                    : lineOrder.prevOf(lineId, direction, current);
            if (next.isEmpty()) {
                break;
            }
            current = next.get();
            out.add(current);
        }
        return out;
    }

    private Optional<Instant> measuredDeparture(String lineId, String direction, String to, String trainNo) {
        try {
            return segmentTimePort.departedAt(lineId, direction, to, trainNo);
        } catch (RuntimeException e) {
            log.debug("구간 출발 기록 조회 실패 line={} to={}: {}", lineId, to, e.getMessage());
            return Optional.empty();
        }
    }

    private String nameOf(String lineId, String stationId) {
        return stationIndex.candidates(lineId).stream()
                .filter(c -> stationId.equals(c.stationId()))
                .map(SubwayPatternStationMatcher.Candidate::stationName)
                .findFirst()
                .orElse(null);
    }
}
