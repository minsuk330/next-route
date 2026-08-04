package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNavigationProperties;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;

import java.time.Instant;

/**
 * 지하철 구간에서 GPS 가 끊긴 동안 만료를 미루는 보호 시각을 정한다(SC-12).
 *
 * <p>지하철 터널에서는 GPS 와 background callback 이 5분 이상 끊긴다. 일반 lease 규칙만 적용하면
 * 앱을 정상적으로 쓰는 사용자의 여정이 지하철을 타는 동안 종료된다. 그래서 "이 시각까지는 조용해도
 * 살아 있다고 본다"를 현재 단계의 예상 종료 시각에서 계산해 둔다.
 *
 * <p>규칙을 이 한 곳에 둔다. 단계 전이(명령)와 진행도 보고(progress) 두 경로가 보호 시각을
 * 건드리는데, 계산식을 양쪽에 각각 두면 언젠가 갈라진다.
 *
 * <pre>
 * 보호 시각 = min(예상 종료 + 5분, 단계 시작 + 60분)
 * </pre>
 *
 * <p>상한이 핵심이다. 앱이 계속 "아직 더 걸린다"고 보고하면 예상 종료는 끝없이 밀릴 수 있는데,
 * 단계 시작을 기준으로 60분을 넘기지 않으므로 시간이 흐른다는 이유만으로 무한히 연장되지는
 * 않는다. 지하철에서 앱을 강제 종료한 경우에도 최대 60분 뒤에는 만료 대상이 된다.
 */
@Component
@RequiredArgsConstructor
public class SubwayOfflineProtectionPolicy {

    private final JourneyNavigationProperties properties;

    /**
     * 현재 단계를 보고 보호 시각을 다시 정한다. 지하철이 아니면 지운다.
     *
     * @param currentLeg     여정이 지금 지나는 단계. {@code null} 이면 보호할 근거가 없다
     * @param observedEndAt  관측으로 보정된 예상 종료 시각. 모르면 {@code null} — 그때는 저장된
     *                       예측값, 그것도 없으면 계획 소요시간으로 내려간다
     * @param at             지금 시각. 단계 시작 시각이 없을 때의 대체값으로만 쓴다
     */
    public void apply(JourneySession journey, JourneyLeg currentLeg,
                      Instant observedEndAt, Instant at) {
        if (currentLeg == null || currentLeg.getType() != JourneyLegType.RIDE_SUBWAY) {
            // 지하철을 벗어났다. 다음 단계까지 보호가 남으면 일반 leg 이 과하게 오래 살아남는다.
            journey.clearSubwayProtection();
            return;
        }

        Instant legStartedAt = currentLeg.getActivatedAt() != null
                ? currentLeg.getActivatedAt() : at;
        Instant predictedEndAt = predictedEndAt(currentLeg, observedEndAt, legStartedAt);

        Instant fromPrediction = predictedEndAt.plus(properties.subwayProtectionBuffer());
        Instant cap = legStartedAt.plus(properties.subwayProtectionCap());
        journey.protectSubwayUntil(fromPrediction.isBefore(cap) ? fromPrediction : cap);
    }

    /**
     * 예상 종료 시각. 아는 것 중 가장 실제에 가까운 값을 쓴다.
     *
     * <p>순서가 근거의 신선도 순이다. 방금 받은 관측 → 저장된 예측 → 검색이 준 계획 소요시간.
     * 계획 소요시간까지 없으면 단계 시작 시각을 그대로 쓴다. 그래도 뒤에 붙는 5분 여유가 남아
     * 최소한의 보호는 생긴다.
     */
    private Instant predictedEndAt(JourneyLeg leg, Instant observedEndAt, Instant legStartedAt) {
        if (observedEndAt != null) {
            return observedEndAt;
        }
        if (leg.getPredictedEndAt() != null) {
            return leg.getPredictedEndAt();
        }
        int plannedSeconds = leg.getPlannedDurationSec() == null ? 0 : leg.getPlannedDurationSec();
        return legStartedAt.plusSeconds(plannedSeconds);
    }
}
