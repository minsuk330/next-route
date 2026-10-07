package watoo.grd.nextroute.application.regionalbus.dto;

import java.time.Duration;

/**
 * 적재 한 번의 결과.
 *
 * @param aborted 포털 공통 오류(키 미등록·한도 초과)로 중간에 멈췄는가
 * @param skipped 이미 다른 적재가 돌고 있어 시작하지 않았는가
 */
public record RegionalBusCatalogLoadResult(
        int routes,
        int loaded,
        int empty,
        int failed,
        int pruned,
        boolean aborted,
        boolean skipped,
        Duration elapsed
) {

    public static RegionalBusCatalogLoadResult skippedRun() {
        return new RegionalBusCatalogLoadResult(0, 0, 0, 0, 0, false, true, Duration.ZERO);
    }
}
