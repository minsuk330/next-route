package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.port.out.CurrentLegArrivalPredictor;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 단계 종류에 맞는 계산 방법을 골라 종료 시각을 구한다.
 *
 * <p>맞는 구현이 없으면 억지로 숫자를 만들지 않고 모른다고 말한다. 기다리는 단계가 지금 그
 * 상태다 — 실시간 도착 정보로 구해야 하는데 그 구현이 아직 없다. 검색 결과의 소요시간에는 대기
 * 시간이 들어 있지 않아서, 그것으로 대신 채우면 근거 없는 숫자가 된다.
 */
@Service
@RequiredArgsConstructor
public class CurrentLegArrivalService {

    private final List<CurrentLegArrivalPredictor> predictors;
    private final Clock clock;

    /**
     * @param geometry 해당 단계의 좌표. 없으면 null 을 넘겨도 된다
     */
    public CurrentLegArrival predict(JourneyLeg leg, LegGeometry geometry) {
        Instant now = Instant.now(clock);
        return predictors.stream()
                .filter(predictor -> predictor.supports(leg.getType()))
                .findFirst()
                .map(predictor -> predictor.predict(leg, geometry, now))
                .orElseGet(CurrentLegArrival::unknown);
    }
}
