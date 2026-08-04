package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Quality;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Source;
import watoo.grd.nextroute.application.journey.dto.TransferTargetArrival;
import watoo.grd.nextroute.application.journey.port.out.TransferTargetArrivalPredictor;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 갈아탈 열차가 사용자 도착 시각에 언제 오는지.
 *
 * <p>버스와 달리 <b>먼 미래를 답할 방법이 없다.</b> 지하철 실시간은 "지금 역으로 들어오는 열차"만
 * 말하고, 지하철에는 도착 예측 모델도 없다(SC-6). 그래서 앵커가 시야 밖이면 모른다고 답한다.
 * 시간표로 대신 채우지 않는다 — 배차가 실제와 어긋나면 그럴듯하게 틀린 시각이 사용자를 움직인다.
 *
 * <p>앵커가 시야 안이면 지금 들어오는 열차를 그대로 쓴다. 몇 분 뒤 도착할 사용자에게도 유효한
 * 근거다 — 그 사이에 열차가 한 대 더 들어오면 다음 평가가 그것을 잡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayTransferTargetArrivalPredictor implements TransferTargetArrivalPredictor {

    private final SubwayRealtimeArrivalReader arrivalReader;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final JourneyEvaluatorProperties properties;

    @Override
    public boolean supports(JourneyLegType type) {
        return type == JourneyLegType.WAIT_SUBWAY;
    }

    @Override
    public TransferTargetArrival predictBoarding(JourneyLeg waitLeg, Instant boardingAnchorAt, Instant now) {
        if (boardingAnchorAt == null || tooFarAhead(boardingAnchorAt, now)) {
            return TransferTargetArrival.unknown();
        }
        if (waitLeg.getRouteId() == null || waitLeg.getStartStopId() == null) {
            return TransferTargetArrival.unknown();
        }

        Integer wayCode = selectedOption(waitLeg).map(JourneyLegRouteOption::getWayCode).orElse(null);
        if (wayCode == null) {
            // 방향을 모르면 반대 방향 열차를 집을 수 있다. 그럴듯하게 틀린 숫자보다 모름이 낫다.
            log.debug("지하철 환승 예측 불가 — legIndex={}, 방향 미확정", waitLeg.getLegIndex());
            return TransferTargetArrival.unknown();
        }

        List<SubwayRealtimeArrivalReader.Arrival> arrivals = arrivalReader.arrivalsByStation(
                waitLeg.getRouteId(), waitLeg.getStartStopId(), waitLeg.getStartName(), wayCode);

        Optional<SubwayRealtimeArrivalReader.Arrival> next = arrivals.stream()
                .filter(SubwayRealtimeArrivalReader.Arrival::hasSeconds)
                .findFirst();
        if (next.isEmpty()) {
            return TransferTargetArrival.unknown();
        }

        int seconds = Math.max(0, next.get().arrivalSeconds());
        Instant arrivalAt = now.plusSeconds(seconds);
        // 지하철은 모델이 없다. 실시간 아니면 답 자체가 없으므로 모델 이름도 없다.
        return TransferTargetArrival.of(new CurrentLegArrival(
                arrivalAt,
                seconds,
                null,
                null,
                Source.REALTIME_ARRIVAL,
                // 사용자가 도착하기 전에 지나갈 열차라면 그 다음 열차를 타게 된다. 값은 주되
                // 근사로 낮춘다 — 다음 배차를 서버가 모르는 상태다.
                arrivalAt.isBefore(boardingAnchorAt) ? Quality.APPROXIMATE : Quality.GOOD,
                now));
    }

    private boolean tooFarAhead(Instant boardingAnchorAt, Instant now) {
        return Duration.between(now, boardingAnchorAt)
                .compareTo(properties.subwayRealtimeHorizon()) > 0;
    }

    private Optional<JourneyLegRouteOption> selectedOption(JourneyLeg leg) {
        if (leg.getSelectedRouteOptionId() == null) {
            return Optional.empty();
        }
        return routeOptionRepository.findById(leg.getSelectedRouteOptionId());
    }
}
