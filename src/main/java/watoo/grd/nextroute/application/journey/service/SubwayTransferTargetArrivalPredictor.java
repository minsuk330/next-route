package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 갈아탈 열차가 사용자 도착 시각에 언제 오는지.
 *
 * <p><b>거리로 미리 자르지 않는다.</b> 지하철 실시간 조회는 호출량 제한이 없어서, 앵커가 멀다는
 * 이유로 묻지 않을 이유가 없다. 물어보고 답이 있으면 쓰고, 없으면 모른다고 한다.
 *
 * <h2>탈 수 있는 열차만 고른다</h2>
 *
 * <p>실시간 응답에는 지금 접근 중인 열차들이 들어 있다. 그중 <b>사용자가 준비를 마치는 시각 이후에
 * 도착하는 첫 열차</b>를 고른다. 그보다 먼저 들어오는 열차는 사용자가 탈 수 없는 열차다.
 *
 * <p>이 규칙이 없으면 조용한 오작동이 생긴다. 30분 뒤에 갈아탈 사용자에게 지금 들어오는 열차를
 * 답으로 주면 여유가 −30분으로 계산돼 <b>"환승 놓침"</b>이 뜬다 — 아직 아무 일도 일어나지 않았는데.
 *
 * <p>탈 수 있는 열차가 목록에 없으면 모른다고 답한다. 시간표로 대신 채우지 않는다(SC-6) — 배차가
 * 실제와 어긋나면 그럴듯하게 틀린 시각이 사용자를 엉뚱한 때에 움직이게 한다. 지하철에는 도착 예측
 * 모델도 없다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayTransferTargetArrivalPredictor implements TransferTargetArrivalPredictor {

    private final SubwayRealtimeArrivalReader arrivalReader;
    private final JourneyLegRouteOptionRepository routeOptionRepository;

    @Override
    public boolean supports(JourneyLegType type) {
        return type == JourneyLegType.WAIT_SUBWAY;
    }

    @Override
    public TransferTargetArrival predictBoarding(JourneyLeg waitLeg, Instant boardingAnchorAt, Instant now) {
        if (boardingAnchorAt == null) {
            // 기준이 없으면 어느 열차가 "탈 수 있는 열차"인지 가릴 수 없다.
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

        Optional<Instant> boardable = arrivals.stream()
                .filter(SubwayRealtimeArrivalReader.Arrival::hasSeconds)
                .map(arrival -> now.plusSeconds(Math.max(0, arrival.arrivalSeconds())))
                .filter(arrivalAt -> !arrivalAt.isBefore(boardingAnchorAt))
                .min(Instant::compareTo);

        if (boardable.isEmpty()) {
            log.debug("지하철 환승 예측 없음 — legIndex={}, 준비 시각 이후 열차가 목록에 없다",
                    waitLeg.getLegIndex());
            return TransferTargetArrival.unknown();
        }

        Instant arrivalAt = boardable.get();
        // 지하철은 모델이 없다. 실시간이 아니면 답 자체가 없으므로 모델 이름도 없다.
        return TransferTargetArrival.of(new CurrentLegArrival(
                arrivalAt,
                (int) Math.max(0, arrivalAt.getEpochSecond() - now.getEpochSecond()),
                null,
                null,
                Source.REALTIME_ARRIVAL,
                Quality.GOOD,
                now));
    }

    private Optional<JourneyLegRouteOption> selectedOption(JourneyLeg leg) {
        if (leg.getSelectedRouteOptionId() == null) {
            return Optional.empty();
        }
        return routeOptionRepository.findById(leg.getSelectedRouteOptionId());
    }
}
