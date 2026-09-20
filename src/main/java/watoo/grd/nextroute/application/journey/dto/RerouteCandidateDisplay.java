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
 * @param alight        <b>어디서 내리는 제안인지</b>(PR-2). 목록의 축이 하차 정류장이라 앱이 이
 *                      값으로 묶어 그린다. 옛 후보·옛 저장 JSON 은 null 이고 그 뜻은 "계획대로
 *                      내린다"이다
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
        List<String> legSummaries,
        AlightStop alight) {

    /** 하차 정보가 없는 후보(옛 저장 JSON)용. 새 칸을 null 로 두는 것이 곧 옛 동작이다. */
    public RerouteCandidateDisplay(Instant destinationEtaAt, int totalDurationSec, int etaGainSec,
                                   int transferCount, int walkingSec, String source,
                                   String confidence, Instant dataObservedAt,
                                   List<String> legSummaries) {
        this(destinationEtaAt, totalDurationSec, etaGainSec, transferCount, walkingSec, source,
                confidence, dataObservedAt, legSummaries, null);
    }

    /**
     * 이 후보를 타려면 내려야 하는 정류장.
     *
     * <p><b>정류장 축 목록을 위한 값이다</b>(journey-reroute-nextstop PR-2). 정류장마다 후보가
     * 하나뿐이라 응답을 정류장으로 <b>중첩</b>하지 않는다 — 껍데기만 한 겹 늘고 앱은 어차피
     * 후보를 그린다. 대신 후보가 자기 하차점을 들고, 앱이 그것으로 구역을 나눈다.
     *
     * @param stopId  서울 정류장 id
     * @param name    화면에 나갈 이름. 못 풀면 null
     * @param alightAt 그 정류장에 <b>내리는</b> 시각(실시간 근거). 계획 하차점이면 환승 준비 시각
     * @param planned 원래 계획된 하차점인가. true 면 "계속 타고 가서 계획대로 내린다"
     */
    public record AlightStop(String stopId, String name, Instant alightAt, boolean planned) {
    }
}
