package watoo.grd.nextroute.application.journey.dto;

import java.time.Instant;
import java.util.List;

/**
 * 앱이 그리는 값. 전부 {@link RerouteCandidateApply} 에서 파생된다(R6).
 *
 * <p>화면이 쓰는 값을 따로 저장하는 이유는 앱이 적용 재료를 해석하지 않게 하기 위해서다. 앱이
 * 경로 원본에서 소요·환승 수를 다시 계산하면 서버와 앱이 다른 숫자를 말할 수 있다.
 *
 * @param etaGainSec    기존 경로 대비 개선폭. <b>같은 스냅샷으로 잰 값이어야 한다</b> —
 *                      시각이 다른 두 예측을 빼면 개선폭이 아니라 시간 차이를 보여 주게 된다
 * @param source        {@code REALTIME} · {@code ML} · {@code STATIC}
 * @param dataObservedAt 이 값을 만든 실시간 관측 시각. 앱이 신선도를 표시한다
 */
public record RerouteCandidateDisplay(
        Instant destinationEtaAt,
        int totalDurationSec,
        int etaGainSec,
        int transferCount,
        int walkingSec,
        String source,
        String confidence,
        Instant dataObservedAt,
        List<String> legSummaries) {
}
