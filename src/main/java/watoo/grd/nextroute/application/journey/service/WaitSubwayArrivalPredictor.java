package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Quality;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Source;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.port.out.CurrentLegArrivalPredictor;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 지하철을 기다리는 단계가 언제 끝나는지 — 즉 열차가 언제 오는지.
 *
 * <p>실시간 열차 snapshot 에서 노선·역·방향이 맞는 열차를 도착 순으로 읽어 가장 빠른 것을 쓴다.
 * 이것은 <b>사용자가 실제로 탈 열차를 확정하는 기능이 아니다</b>(SC-6). 가장 일관된 후보로 ETA 를
 * 다시 기준 잡는 것이고, 후보가 바뀌면 최신 ETA 로 수렴한다.
 *
 * <p>방향을 가릴 수 없으면 열차를 고르지 않는다. 반대 방향 열차를 집으면 ETA 가 그럴듯하게
 * 틀리고, 사용자는 그 숫자를 믿고 엉뚱한 시각에 움직인다.
 *
 * <p>매칭 실패·snapshot 부재에서 정적 대기시간을 만들어 내지 않는다(SC-6 4항·SC-9 8항).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitSubwayArrivalPredictor implements CurrentLegArrivalPredictor {

    private final SubwayRealtimeArrivalReader arrivalReader;
    private final JourneyLegRouteOptionRepository routeOptionRepository;

    @Override
    public boolean supports(JourneyLegType type) {
        return type == JourneyLegType.WAIT_SUBWAY;
    }

    @Override
    public CurrentLegArrival predict(JourneyLeg leg, LegGeometry geometry, Instant now) {
        if (leg.getState() != JourneyLegState.ACTIVE) {
            return CurrentLegArrival.unknown();
        }
        if (leg.getRouteId() == null || leg.getStartStopId() == null) {
            return CurrentLegArrival.unknown();
        }

        Integer wayCode = selectedOption(leg).map(JourneyLegRouteOption::getWayCode).orElse(null);
        if (wayCode == null) {
            // 방향을 모르면 반대 방향 열차를 집을 수 있다. 그럴듯하게 틀린 숫자보다 모름이 낫다.
            log.debug("대기 지하철 ETA 조회 불가 — legIndex={}, 방향 미확정", leg.getLegIndex());
            return CurrentLegArrival.unknown();
        }

        List<SubwayRealtimeArrivalReader.Arrival> arrivals =
                arrivalReader.arrivals(leg.getRouteId(), leg.getStartStopId(), wayCode);

        Optional<SubwayRealtimeArrivalReader.Arrival> next = arrivals.stream()
                .filter(SubwayRealtimeArrivalReader.Arrival::hasSeconds)
                .findFirst();
        if (next.isEmpty()) {
            return CurrentLegArrival.unknown();
        }

        int seconds = Math.max(0, next.get().arrivalSeconds());
        return new CurrentLegArrival(
                now.plusSeconds(seconds),
                seconds,
                null,
                null,
                Source.REALTIME_ARRIVAL,
                Quality.GOOD,
                now);
    }

    private Optional<JourneyLegRouteOption> selectedOption(JourneyLeg leg) {
        if (leg.getSelectedRouteOptionId() == null) {
            return Optional.empty();
        }
        return routeOptionRepository.findById(leg.getSelectedRouteOptionId());
    }
}
