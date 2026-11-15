package watoo.grd.nextroute.application.regionalsubway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.regionalsubway.config.RegionalSubwayServingProperties;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparturesView;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * 여정의 지하철 대기 단계가 지방 노선이면 공식 시간표로 다음 열차를 고른다(SC-6 의 지방 노선 예외, PLAN 10절 B1·B2).
 *
 * <p>지방 단계에는 {@code startStopId/endStopId}(서울 역 id)가 없다. OTP 정류장 id 는 {@code sourceStartStopId} 에만 있다.
 * 타는 역 = 대기 단계의 {@code sourceStartStopId}, 내릴 역 = 바로 다음 RIDE_SUBWAY 단계의 {@code sourceEndStopId}.
 * 둘 중 하나라도 지방 역이 아니면(서울 노선) empty — 서울 노선은 실시간 경로만 탄다.
 *
 * <p>serving 플래그가 꺼져 있으면 empty. 옛 앱은 시간표 값을 라벨 없이 보이기 때문이다(검토 M12).
 */
@Component
@RequiredArgsConstructor
public class RegionalLegWaitReader {

    private final RegionalDepartureQueryService queryService;
    private final RegionalStationSeedCatalog catalog;
    private final RegionalSubwayServingProperties serving;
    private final JourneyLegRepository legRepository;

    /**
     * @param anchor 이 시각 이후 첫 열차(대기 중이면 지금, 환승 목표면 환승역에 닿는 시각)
     */
    public Optional<RegionalDeparturesView.Departure> nextDeparture(JourneyLeg waitLeg, Instant anchor) {
        if (!serving.isEnabled() || waitLeg.getType() != JourneyLegType.WAIT_SUBWAY) {
            return Optional.empty();
        }
        String board = waitLeg.getSourceStartStopId();
        if (catalog.findByGtfsStopId(board).isEmpty()) {
            return Optional.empty();   // 서울 노선(또는 모르는 정류장) — DB 를 읽지 않는다
        }
        Optional<String> alight = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(waitLeg.getJourneyId(), waitLeg.getRouteRevision())
                .stream()
                .filter(l -> l.getLegIndex() == waitLeg.getLegIndex() + 1 && l.getType() == JourneyLegType.RIDE_SUBWAY)
                .map(JourneyLeg::getSourceEndStopId)
                .findFirst();
        if (alight.isEmpty()) {
            return Optional.empty();
        }
        return queryService.nextForLeg(board, alight.get(), 1, anchor)
                .flatMap(v -> v.directions().stream().findFirst())
                .flatMap(d -> d.departures().stream().findFirst());
    }
}
