package watoo.grd.nextroute.application.regionalsubway.dto;

import java.time.Duration;

/**
 * 한 번의 적재 결과.
 *
 * @param stations          대상 역 수
 * @param loaded            출발 행을 교체한 역 수
 * @param empty             원천이 0건이라 옛 값을 그대로 둔 역 수
 * @param failed            원천 호출이 실패해 옛 값을 그대로 둔 역 수
 * @param unmatchedTerminal 종착역 이름을 시드에서 못 찾아 버린 열차 수
 * @param rows              저장한 출발 행 수
 */
public record RegionalSubwayLoadResult(int stations, int loaded, int empty, int failed,
                                       int unmatchedTerminal, int rows, boolean skipped, Duration elapsed) {

    public static RegionalSubwayLoadResult skippedRun() {
        return new RegionalSubwayLoadResult(0, 0, 0, 0, 0, 0, true, Duration.ZERO);
    }
}
