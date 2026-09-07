package watoo.grd.nextroute.application.route.routing;

import java.time.Instant;

/**
 * 실시간/ML 보강 상태. coverage(능력)와 actualSource(이번 출처)를 구분해 담는다.
 *
 * @param coverage     이 leg 의 실시간/모델 지원 능력
 * @param actualSource 이번 계산의 실제 출처(STATIC/REALTIME/MODEL)
 * @param observedAt   실시간 관측 시각(있으면)
 * @param calculatedAt 이 보강을 계산한 시각
 * @param status       freshness/status 라벨(예: FRESH/STALE/CANCELLED)
 */
public record Enrichment(
        RealtimeCoverage coverage,
        ActualSource actualSource,
        Instant observedAt,
        Instant calculatedAt,
        String status
) {

    /** 정적 시간표만(보강 없음). */
    public static Enrichment staticOnly(RealtimeCoverage coverage) {
        return new Enrichment(coverage, ActualSource.STATIC, null, null, "STATIC");
    }
}
