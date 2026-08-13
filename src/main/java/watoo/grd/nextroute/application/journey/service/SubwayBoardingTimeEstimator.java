package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.BoardingEstimate;
import watoo.grd.nextroute.application.journey.dto.BoardingOutcome;
import watoo.grd.nextroute.application.journey.dto.BoardingQuery;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Quality;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Source;
import watoo.grd.nextroute.application.journey.port.out.BoardingTimeEstimator;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 지하철 승차 시각 추정. <b>실시간만 본다.</b>
 *
 * <p>거리로 미리 자르지 않는다 — 지하철 실시간 조회는 호출량 제한이 없어서 앵커가 멀다는 이유로
 * 묻지 않을 이유가 없다. 실시간 응답 중 <b>사용자가 준비를 마치는 시각 이후에 도착하는 첫 열차</b>를
 * 고르고, 없으면 모른다고 답한다. 시간표로 대신 채우지 않는다(SC-6) — 배차가 실제와 어긋나면
 * 그럴듯하게 틀린 시각이 사용자를 엉뚱한 때에 움직이게 한다. 지하철에는 도착 예측 모델도 없다.
 *
 * <p>{@code blocked} 는 항상 false 다. 호출량 제한이 없어 "막힘" 개념 자체가 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayBoardingTimeEstimator implements BoardingTimeEstimator {

    private final SubwayRealtimeArrivalReader arrivalReader;

    @Override
    public boolean supports(JourneyLegType waitType) {
        return waitType == JourneyLegType.WAIT_SUBWAY;
    }

    @Override
    public BoardingOutcome estimate(BoardingQuery query, Instant anchorAt, Instant now, long budgetMs) {
        if (anchorAt == null || query.routeId() == null || query.stopId() == null) {
            return BoardingOutcome.none();
        }
        if (query.wayCode() == null) {
            // 방향을 모르면 반대 방향 열차를 집을 수 있다. 그럴듯하게 틀린 숫자보다 모름이 낫다.
            log.debug("지하철 승차 추정 불가 — stopId={}, 방향 미확정", query.stopId());
            return BoardingOutcome.none();
        }

        List<SubwayRealtimeArrivalReader.Arrival> arrivals = arrivalReader.arrivalsByStation(
                query.routeId(), query.stopId(), query.stopName(), query.wayCode());

        Optional<Instant> boardable = arrivals.stream()
                .filter(SubwayRealtimeArrivalReader.Arrival::hasSeconds)
                .map(arrival -> now.plusSeconds(Math.max(0, arrival.arrivalSeconds())))
                .filter(arrivalAt -> !arrivalAt.isBefore(anchorAt))
                .min(Instant::compareTo);

        if (boardable.isEmpty()) {
            log.debug("지하철 승차 추정 없음 — stopId={}, 준비 시각 이후 열차가 목록에 없다", query.stopId());
            return BoardingOutcome.none();
        }
        // 리더가 이미 앵커 이후 열차만 골랐으므로, 값이 있다는 것이 곧 탈 수 있다는 뜻이다.
        return BoardingOutcome.of(new BoardingEstimate(
                boardable.get(), true, Source.REALTIME_ARRIVAL, Quality.GOOD, now, null));
    }
}
