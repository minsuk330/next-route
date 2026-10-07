package watoo.grd.nextroute.application.journey.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 진행 링의 <b>내부</b> 상태. Redis 에 그대로 담긴다.
 *
 * <p>외부로 나가는 {@link SubwayGuidance} 와 <b>다른 값이다.</b> 여기에는 판정용 키가 더 들어
 * 있다 — 다음 역이 바뀌었는지를 <b>표시 이름으로 판정하지 않기</b> 위해서다. 역 이름은 표기가
 * 흔들려서({@code 성수}/{@code 성수종착}/{@code 성수지선}) 같은 역인데 링이 0 으로 리셋된다.
 *
 * <p>fencing 네 값({@code journeyId}·{@code routeRevision}·{@code legIndex}·
 * {@code bindingVersion})을 상태 안에도 들고 있는 이유는, 키에서 되읽은 값과 대조해 <b>옛
 * worker 가 쓴 상태</b>를 알아보기 위해서다.
 *
 * @param nextStationSequence     승차역을 0 으로 세는 계획 역 체인의 순번. 판정 축이다
 * @param canonicalNextStationKey 서울 실시간 {@code statnId}. 도착 조회의 인자로도 그대로 쓴다
 * @param segmentProgress         {@code progressBaseAt} <b>시점의</b> 확정 진행률. 지금 값이 아니다
 * @param progressTarget          앱이 보간해 갈 상한
 * @param estimatedNextStationAt  실시간 기반 다음 역 도착 예정. <b>없으면 null</b> — 앱이 보간을 멈춘다
 * @param progressValidUntil      앱이 로컬 보간을 이어가도 되는 마지막 시각
 * @param observedAt              원천이 말한 관측 시각. 도착 API 는 <b>그 행의</b> {@code recptnDt} 다
 * @param guidanceVersion         화면이 마지막으로 <b>눈에 띄게</b> 바뀐 시각(epoch millis, #307). 같은 화면이면
 *                                tick 이 돌아도 그대로다. 고정 세대가 바뀌어 키가 새로 생겨도 시각이라 단조다.
 *                                0 = 아직 부여 전(배포 전 Redis 값 포함)
 */
public record SubwayRingState(
        Long journeyId,
        int routeRevision,
        int legIndex,
        int bindingVersion,

        int nextStationSequence,
        String canonicalNextStationKey,
        String nextStationName,
        String alightingStationName,
        Integer remainingStations,

        BigDecimal segmentProgress,
        Instant progressBaseAt,
        BigDecimal progressTarget,
        Instant estimatedNextStationAt,
        Instant progressValidUntil,

        SubwayRingPhase phase,
        SubwayRingQuality quality,
        Instant observedAt,
        Instant updatedAt,
        long guidanceVersion
) {

    /** 버전 없이 만든다(계산기). 버전은 저장 직전에 갱신기가 붙인다. */
    public SubwayRingState(Long journeyId, int routeRevision, int legIndex, int bindingVersion,
                           int nextStationSequence, String canonicalNextStationKey,
                           String nextStationName, String alightingStationName,
                           Integer remainingStations, BigDecimal segmentProgress,
                           Instant progressBaseAt, BigDecimal progressTarget,
                           Instant estimatedNextStationAt, Instant progressValidUntil,
                           SubwayRingPhase phase, SubwayRingQuality quality,
                           Instant observedAt, Instant updatedAt) {
        this(journeyId, routeRevision, legIndex, bindingVersion, nextStationSequence,
                canonicalNextStationKey, nextStationName, alightingStationName, remainingStations,
                segmentProgress, progressBaseAt, progressTarget, estimatedNextStationAt,
                progressValidUntil, phase, quality, observedAt, updatedAt, 0L);
    }

    public SubwayRingState withGuidanceVersion(long version) {
        return new SubwayRingState(journeyId, routeRevision, legIndex, bindingVersion,
                nextStationSequence, canonicalNextStationKey, nextStationName,
                alightingStationName, remainingStations, segmentProgress, progressBaseAt,
                progressTarget, estimatedNextStationAt, progressValidUntil, phase, quality,
                observedAt, updatedAt, version);
    }

    /** 이 상태가 그 고정 세대의 것인지. 아니면 옛 worker 가 쓴 값이다. */
    public boolean matches(int routeRevision, int legIndex, int bindingVersion) {
        return this.routeRevision == routeRevision
                && this.legIndex == legIndex
                && this.bindingVersion == bindingVersion;
    }

    /**
     * 다음 역에 <b>도착까지 마친</b> 상태인지.
     *
     * <p>따로 칸을 두지 않고 진행률 1.00 자체를 표시로 쓴다. 상한 0.90(이동 중)·0.95(진입)는
     * 실제 도착 사건 없이는 만들어지지 않으므로, 1.00 이면 도착 사건을 본 것이다. 다음 tick 이
     * 이 값을 보고 구간을 넘긴다.
     */
    /**
     * 직전 상태보다 <b>다음 역 순번이 앞으로 갔는가</b> — 역 하나를 지났다(#264 재탐색 보드 신호).
     *
     * <p>고정·개정본·단계가 다른 직전 상태와는 비교하지 않는다. 다른 링의 순번을 견주면 엉뚱한 "지남"이 된다.
     */
    public boolean advancedFrom(SubwayRingState previous) {
        return previous != null
                && previous.matches(routeRevision, legIndex, bindingVersion)
                && nextStationSequence > previous.nextStationSequence;
    }

    public boolean arrivedAtNextStation() {
        return segmentProgress != null && segmentProgress.compareTo(BigDecimal.ONE) >= 0;
    }
}
