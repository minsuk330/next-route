package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Quality;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Source;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.port.out.CurrentLegArrivalPredictor;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalLegWaitReader;
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
 * <p><b>예외 — 지방 지하철</b>(부산·대구·대전·광주): 서울 노선 id 가 없는 단계는 실시간 소스가 아예 없어서 운영사 공식 시간표의
 * 다음 열차를 {@code TIMETABLE}·{@code APPROXIMATE} 로 준다({@code RegionalLegWaitReader}, docs/plan/subway-regional-timetable).
 * 서울 노선은 이 경로에 가지 않는다.
 *
 * <p>매칭 실패·조회 실패에서 정적 대기시간을 만들어 내지 않는다(SC-6 4항·SC-9 8항).
 *
 * <p>실시간 수집기가 꺼져 있는 환경을 전제해 <b>역별 실시간 호출</b>을 쓴다. 캐시를 먼저 보지
 * 않는 이유는 비어 있는 캐시를 확인하는 왕복이 늘 헛수고이기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WaitSubwayArrivalPredictor implements CurrentLegArrivalPredictor {

    private final SubwayRealtimeArrivalReader arrivalReader;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final RegionalLegWaitReader regionalReader;

    @Override
    public boolean supports(JourneyLegType type) {
        return type == JourneyLegType.WAIT_SUBWAY;
    }

    @Override
    public CurrentLegArrival predict(JourneyLeg leg, LegGeometry geometry, Instant now) {
        if (leg.getState() != JourneyLegState.ACTIVE) {
            return CurrentLegArrival.unknown();
        }
        if (!leg.hasSubwayRealtimeLine()) {
            // 서울 노선 id 가 없다 = 지방 지하철일 수 있다. 실시간 소스가 없어 공식 시간표로만 답한다(서울 노선은 여기서 empty)
            return regionalTimetable(leg, now);
        }
        if (leg.getStartStopId() == null) {
            return CurrentLegArrival.unknown();
        }

        Integer wayCode = selectedOption(leg).map(JourneyLegRouteOption::getWayCode).orElse(null);
        if (wayCode == null) {
            // 방향을 모르면 반대 방향 열차를 집을 수 있다. 그럴듯하게 틀린 숫자보다 모름이 낫다.
            log.debug("대기 지하철 ETA 조회 불가 — legIndex={}, 방향 미확정", leg.getLegIndex());
            return CurrentLegArrival.unknown();
        }

        // 수집기가 꺼져 있으면 캐시가 항상 비어 늘 "모름"이 된다. 그래서 필요한 역만 실시간으로
        // 직접 부른다. 호출량은 조회하는 역 수에 비례한다.
        List<SubwayRealtimeArrivalReader.Arrival> arrivals = arrivalReader.arrivalsByStation(
                leg.getRouteId(), leg.getStartStopId(), leg.getStartName(), wayCode);

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

    private CurrentLegArrival regionalTimetable(JourneyLeg leg, Instant now) {
        if (regionalReader == null) {
            return CurrentLegArrival.unknown();
        }
        return regionalReader.nextDeparture(leg, now)
                .map(d -> {
                    int seconds = (int) Math.max(0, d.scheduledAt().getEpochSecond() - now.getEpochSecond());
                    return new CurrentLegArrival(d.scheduledAt(), seconds, null, null,
                            Source.TIMETABLE, Quality.APPROXIMATE, now);
                })
                .orElseGet(CurrentLegArrival::unknown);
    }

    private Optional<JourneyLegRouteOption> selectedOption(JourneyLeg leg) {
        if (leg.getSelectedRouteOptionId() == null) {
            return Optional.empty();
        }
        return routeOptionRepository.findById(leg.getSelectedRouteOptionId());
    }
}
